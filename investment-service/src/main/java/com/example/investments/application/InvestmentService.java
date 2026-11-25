package com.example.investments.application;

import com.example.investments.application.port.Broker;
import com.example.investments.application.port.CommandLog;
import com.example.investments.application.port.Holdings;
import com.example.investments.application.port.Instruments;
import com.example.investments.application.port.Orders;
import com.example.investments.application.port.InvestmentMetrics;
import com.example.investments.application.port.Payouts;
import com.example.investments.application.port.PortfolioEvents;
import com.example.investments.application.port.Portfolios;
import com.example.investments.application.port.ProcessedMessages;
import com.example.investments.application.port.TransferRequests;
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
 * service and the broker answer is handled by {@link MoneyMovements} and {@link TradingDesk}.
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
    private final PortfolioEvents events;
    private final TransferRequests transfers;
    private final Broker broker;
    private final CommandLog commandLog;
    private final InvestmentMetrics metrics;

    public InvestmentService(Portfolios portfolios, Orders orders, Holdings holdings, Payouts payouts,
            Instruments instruments, ProcessedMessages processed, PortfolioEvents events, TransferRequests transfers,
            Broker broker, CommandLog commandLog, InvestmentMetrics metrics) {
        this.portfolios = portfolios;
        this.orders = orders;
        this.holdings = holdings;
        this.payouts = payouts;
        this.instruments = instruments;
        this.processed = processed;
        this.events = events;
        this.transfers = transfers;
        this.broker = broker;
        this.commandLog = commandLog;
        this.metrics = metrics;
    }

    // --- intake: accepts into the command log, changes nothing (ADR 0019) -------------------------
    // Not transactional: the Kafka send may wait seconds for its acknowledgement, and would hold a
    // database connection all that time.

    /**
     * @return the portfolio if it was already opened, empty if the command was just appended
     * @throws PortfolioIdInUseException if the id belongs to a portfolio opened with different details
     */
    public Optional<Portfolio> submit(Command.OpenPortfolio open) {
        var existing = openedBefore(portfolios.find(open.portfolioId()), open);
        if (existing.isEmpty()) {
            commandLog.append(open);
        }
        return existing;
    }

    /**
     * @return the order if it was already applied, empty if the command was just appended
     * @throws UnknownPortfolioException if the portfolio isn't open (yet)
     * @throws OrderIdInUseException     if the id belongs to a different order
     */
    public Optional<Order> submit(Command.PlaceOrder place) {
        var existing = placedBefore(place.request());
        if (existing.isPresent()) {
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
            metrics.duplicateMessage();
            return;
        }
        switch (command) {
            case Command.OpenPortfolio open -> open(open);
            case Command.PlaceOrder place -> place(place.request());
        }
    }

    private void open(Command.OpenPortfolio open) {
        if (openedBefore(portfolios.lock(open.portfolioId()), open).isPresent()) {
            log.info("Portfolio {} is already open; skipping the repeated opening", open.portfolioId());
            return;
        }
        var portfolio = Portfolio.open(open.portfolioId(), open.customerId(), open.referenceAccountId(),
                open.acceptedAt());
        portfolios.add(portfolio);
        events.opened(portfolio);
    }

    /**
     * @return the portfolio, if it was opened before with these details
     * @throws PortfolioIdInUseException if it was opened with others
     */
    private static Optional<Portfolio> openedBefore(Optional<Portfolio> existing, Command.OpenPortfolio open) {
        if (existing.isPresent() && !existing.get().openedAs(open.customerId(), open.referenceAccountId())) {
            throw new PortfolioIdInUseException(open.portfolioId());
        }
        return existing;
    }

    /**
     * @return the order, if it was placed before as asked now
     * @throws OrderIdInUseException if its id was used for a different order
     */
    private Optional<Order> placedBefore(Order.Request request) {
        var existing = orders.find(request.orderId());
        if (existing.isPresent() && !existing.get().request().sameOrderAs(request)) {
            throw new OrderIdInUseException(request.orderId());
        }
        return existing;
    }

    private void place(Order.Request request) {
        var portfolio = portfolios.lock(request.portfolioId());
        if (placedBefore(request).isPresent()) {
            log.info("Order {} was already placed; skipping", request.orderId());
            metrics.duplicateMessage();
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
        metrics.orderReceived(order);
        transfers.request(portfolio.funding(order));
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
        metrics.orderReceived(order);
        broker.place(order, request.acceptedAt());
    }

    private void reject(Order.Request request, String reason) {
        log.info("Order {} rejected: {}", request.orderId(), reason);
        var order = Order.rejected(request, reason);
        orders.add(order);
        metrics.orderReceived(order);
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
        var entries = portfolios.entries(portfolioId, beforeEntryNo, limit);
        if (entries.isEmpty()) {
            // Only a page without entries needs to tell an empty ledger from an unknown portfolio.
            portfolios.find(portfolioId).orElseThrow(() -> new UnknownPortfolioException(portfolioId));
        }
        return entries;
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
}
