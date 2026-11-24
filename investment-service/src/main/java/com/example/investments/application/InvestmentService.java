package com.example.investments.application;

import com.example.investments.application.port.CommandLog;
import com.example.investments.application.port.Holdings;
import com.example.investments.application.port.Instruments;
import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Outbound;
import com.example.investments.application.port.Payouts;
import com.example.investments.application.port.Portfolios;
import com.example.investments.application.port.ProcessedMessages;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Fill;
import com.example.investments.domain.Holding;
import com.example.investments.domain.Instrument;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.TradingCalendar;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portfolios and orders as customers see them (ADR 0026): the REST intake appends to the command log,
 * the commands are applied when consumed, and the queries read what was applied. What the transfer
 * service and the broker answer is handled by {@link MoneyMovements} and {@link Executions}.
 */
@Service
public class InvestmentService {

    private static final Logger log = LoggerFactory.getLogger(InvestmentService.class);

    private final Portfolios portfolios;
    private final Orders orders;
    private final Holdings holdings;
    private final Payouts payouts;
    private final Instruments instruments;
    private final ProcessedMessages processed;
    private final Outbound outbound;
    private final CommandLog commandLog;

    public InvestmentService(Portfolios portfolios, Orders orders, Holdings holdings, Payouts payouts,
            Instruments instruments, ProcessedMessages processed, Outbound outbound, CommandLog commandLog) {
        this.portfolios = portfolios;
        this.orders = orders;
        this.holdings = holdings;
        this.payouts = payouts;
        this.instruments = instruments;
        this.processed = processed;
        this.outbound = outbound;
        this.commandLog = commandLog;
    }

    // --- intake: accepts into the command log, changes nothing (ADR 0019) -------------------------
    // Not transactional: the Kafka send may wait seconds for its acknowledgement, and would hold a
    // database connection all that time.

    /**
     * @return the portfolio if it was already opened, empty if the command was just appended
     * @throws PortfolioIdInUseException if the id belongs to a portfolio opened with different details
     */
    public Optional<Portfolio> submit(Command.OpenPortfolio open) {
        var existing = portfolios.find(open.portfolioId());
        if (existing.isPresent()) {
            if (!existing.get().openedAs(open.customerId(), open.referenceAccountId())) {
                throw new PortfolioIdInUseException(open.portfolioId());
            }
            return existing;
        }
        commandLog.append(open);
        return Optional.empty();
    }

    /**
     * @return the order if it was already applied, empty if the command was just appended
     * @throws UnknownPortfolioException if the portfolio isn't open (yet)
     * @throws OrderIdInUseException     if the id belongs to a different order
     */
    public Optional<Order> submit(Command.PlaceOrder place) {
        var existing = orders.find(place.request().orderId());
        if (existing.isPresent()) {
            if (!existing.get().request().sameOrderAs(place.request())) {
                throw new OrderIdInUseException(place.request().orderId());
            }
            return existing;
        }
        if (portfolios.find(place.portfolioId()).isEmpty()) {
            throw new UnknownPortfolioException(place.portfolioId());
        }
        commandLog.append(place);
        return Optional.empty();
    }

    // --- applying commands ------------------------------------------------------------------------

    /**
     * Applies a command from the command log. An id in use with different details is a conflict a
     * person must look at: the exception dead-letters the command.
     */
    @Transactional
    public void apply(String commandId, Command command) {
        if (!processed.markProcessed("command:" + commandId)) {
            log.info("Skipping already applied command {}", commandId);
            return;
        }
        switch (command) {
            case Command.OpenPortfolio open -> open(open);
            case Command.PlaceOrder place -> place(place.request());
        }
    }

    private void open(Command.OpenPortfolio open) {
        var existing = portfolios.lock(open.portfolioId());
        if (existing.isPresent()) {
            if (!existing.get().openedAs(open.customerId(), open.referenceAccountId())) {
                throw new PortfolioIdInUseException(open.portfolioId());
            }
            log.info("Portfolio {} is already open; skipping the repeated opening", open.portfolioId());
            return;
        }
        var portfolio = Portfolio.open(open.portfolioId(), open.customerId(), open.referenceAccountId(),
                open.acceptedAt());
        portfolios.add(portfolio);
        outbound.portfolioOpened(portfolio);
    }

    private void place(Order.Request request) {
        var portfolio = portfolios.lock(request.portfolioId());
        var earlier = orders.find(request.orderId());
        if (earlier.isPresent()) {
            if (!earlier.get().request().sameOrderAs(request)) {
                throw new OrderIdInUseException(request.orderId());
            }
            log.info("Order {} was already placed; skipping", request.orderId());
            return;
        }
        if (portfolio.isEmpty()) {
            reject(request, Order.UNKNOWN_PORTFOLIO);
            return;
        }
        var instrument = instruments.find(request.isin());
        if (instrument.isEmpty()) {
            reject(request, Order.UNKNOWN_INSTRUMENT);
            return;
        }
        if (request.side() == Order.Side.BUY) {
            buy(portfolio.get(), instrument.get(), request);
        } else {
            sell(request);
        }
    }

    /** Money first: the order goes to the broker once its money has arrived (ADR 0026). */
    private void buy(Portfolio portfolio, Instrument instrument, Order.Request request) {
        if (!instrument.onSaleAt(request.acceptedAt())) {
            reject(request, Order.NOT_ON_SALE);
            return;
        }
        var order = Order.buy(request);
        orders.add(order);
        outbound.requestTransfer(Order.fundingTransferId(order.id()), portfolio.customerId(),
                portfolio.referenceAccountId(), portfolio.id(), request.amount(), request.acceptedAt());
    }

    /** Reserves the units, so a second sell can't sell them too, and places the order at once. */
    private void sell(Order.Request request) {
        var holding = holdings.get(request.portfolioId(), request.isin());
        if (!holding.reserve(request.quantity())) {
            reject(request, Order.NOT_ENOUGH_UNITS);
            return;
        }
        holdings.save(holding);
        var order = Order.sell(request, TradingCalendar.tradingDayOf(request.acceptedAt()));
        orders.add(order);
        outbound.placeWithBroker(order, request.acceptedAt());
    }

    private void reject(Order.Request request, String reason) {
        log.info("Order {} rejected: {}", request.orderId(), reason);
        orders.add(Order.rejected(request, reason));
    }

    // --- queries ----------------------------------------------------------------------------------

    /** A portfolio with what it holds and what it owes. */
    public record PortfolioView(Portfolio portfolio, List<Holding> holdings, List<Payout> unpaidPayouts) {
    }

    /** An order with its fills, oldest first. */
    public record OrderView(Order order, List<Fill> fills) {
    }

    @Transactional(readOnly = true)
    public PortfolioView portfolio(UUID portfolioId) {
        var portfolio = portfolios.find(portfolioId).orElseThrow(() -> new UnknownPortfolioException(portfolioId));
        return new PortfolioView(portfolio, holdings.of(portfolioId), payouts.unpaid(portfolioId));
    }

    @Transactional(readOnly = true)
    public List<CashEntry> transactions(UUID portfolioId, Long beforeEntryNo, int limit) {
        portfolios.find(portfolioId).orElseThrow(() -> new UnknownPortfolioException(portfolioId));
        return portfolios.entries(portfolioId, beforeEntryNo, limit);
    }

    @Transactional(readOnly = true)
    public OrderView order(String orderId) {
        var order = orders.find(orderId).orElseThrow(() -> new UnknownOrderException(orderId));
        return new OrderView(order, orders.fills(orderId));
    }

    /** Instruments on sale now. */
    @Transactional(readOnly = true)
    public List<Instrument> instrumentsOnSale(Instant at) {
        return instruments.all().stream().filter(instrument -> instrument.onSaleAt(at)).toList();
    }

    /** Orders still with the broker after their trading day ended (ADR 0027's alert). */
    @Transactional(readOnly = true)
    public long countOverdueOrders(Instant now) {
        return orders.countTradingSince(TradingCalendar.businessDayOf(now));
    }

    @Transactional(readOnly = true)
    public long countUnpaidPayoutsSince(Instant before) {
        return payouts.countUnpaidSince(before);
    }
}
