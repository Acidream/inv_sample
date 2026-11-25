package com.example.investments.application;

import com.example.investments.application.port.Holdings;
import com.example.investments.application.port.InvestmentMetrics;
import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Portfolios;
import com.example.investments.application.port.ProcessedMessages;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.IllegalExecutionException;
import com.example.investments.domain.Lot;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies what the broker reports about orders (ADR 0026, 0027). A buy fill pays the broker from the
 * money the order brought and adds a lot; a sell fill takes lots first in, first out and books the
 * proceeds. When an order ends, whatever money is left over or came in is paid out to the reference
 * account, and a sell's unsold units are released.
 */
@Service
public class TradingDesk {

    private static final Logger log = LoggerFactory.getLogger(TradingDesk.class);

    private final Portfolios portfolios;
    private final Orders orders;
    private final Holdings holdings;
    private final ProcessedMessages processed;
    private final Books books;
    private final PayoutDesk desk;
    private final InvestmentMetrics metrics;

    TradingDesk(Portfolios portfolios, Orders orders, Holdings holdings, ProcessedMessages processed, Books books,
            PayoutDesk desk, InvestmentMetrics metrics) {
        this.portfolios = portfolios;
        this.orders = orders;
        this.holdings = holdings;
        this.processed = processed;
        this.books = books;
        this.desk = desk;
        this.metrics = metrics;
    }

    /**
     * @throws IllegalExecutionException if the report contradicts the order (dead-lettered): an
     *                                   unknown order, a fill after the end or beyond what was ordered
     */
    @Transactional
    public void apply(BrokerReport report) {
        if (!processed.markProcessed("execution:" + report.messageId())) {
            log.info("Skipping already applied broker report {}", report.messageId());
            metrics.duplicateMessage();
            return;
        }
        var portfolioId = orders.find(report.orderId())
                .orElseThrow(() -> new IllegalExecutionException("Broker reported on unknown order "
                        + report.orderId()))
                .portfolioId();
        var ledger = books.of(portfolios.lock(portfolioId).orElseThrow());
        // Read again under the portfolio's lock: another report may have changed it meanwhile.
        var order = orders.find(report.orderId()).orElseThrow();
        switch (report) {
            case BrokerReport.Accepted accepted -> log.debug("Broker accepted order {}", order.id());
            case BrokerReport.Filled filled -> filled(ledger, order, filled);
            case BrokerReport.Rejected rejected -> {
                if (order.rejectedByBroker(rejected.reason())) {
                    log.info("Broker rejected order {}: {}", order.id(), rejected.reason());
                    ended(ledger, order, report.at());
                }
            }
            case BrokerReport.Expired expired -> {
                if (order.expired()) {
                    log.info("Order {} expired after filling {}", order.id(), order.filledQuantity());
                    ended(ledger, order, report.at());
                }
            }
        }
        orders.update(order);
        ledger.save();
    }

    private void filled(Books.Ledger ledger, Order order, BrokerReport.Filled report) {
        var execution = report.execution();
        var earlier = orders.findFill(order.id(), execution.execId());
        if (earlier.isPresent()) {
            if (!earlier.get().reports(execution)) {
                throw new IllegalExecutionException("Execution " + execution.execId() + " of order " + order.id()
                        + " was reported before with other details");
            }
            log.info("Execution {} of order {} was already applied; skipping", execution.execId(), order.id());
            metrics.duplicateMessage();
            return;
        }
        var fill = order.filled(execution, report.done());
        var holding = holdings.get(order.portfolioId(), order.isin());
        if (order.side() == Order.Side.BUY) {
            holding.bought(Lot.bought(fill, order.id()), fill.price());
        } else {
            fill = fill.withRealizedGain(holding.sold(fill.quantity(), fill.price(), fill.amount()).realizedGain());
        }
        if (fill.movesMoney()) {
            ledger.book(CashEntry.Booking.fill(order, fill));
        } else {
            log.info("Fill {} of order {} is worth less than a cent; no money moves", fill.execId(), order.id());
        }
        boolean ended = order.status().isFinal();
        if (ended) {
            holding.release(order.unsoldUnits());
        }
        holdings.save(holding);
        orders.addFill(order.id(), fill);
        metrics.filled(order.side(), fill.amount());
        if (ended) {
            settle(ledger, order, report.at());
        }
    }

    /** Trading is over without a fill: release unsold units, pay out what is left over or came in. */
    private void ended(Books.Ledger ledger, Order order, Instant at) {
        var unsold = order.unsoldUnits();
        if (unsold.isPositive()) {
            var holding = holdings.get(order.portfolioId(), order.isin());
            holding.release(unsold);
            holdings.save(holding);
        }
        settle(ledger, order, at);
    }

    /** Pays out what is left over or came in once trading is over. */
    private void settle(Books.Ledger ledger, Order order, Instant at) {
        metrics.orderEnded(order);
        order.moneyToPayOut().ifPresentOrElse(
                money -> desk.payOut(ledger.portfolio(), Payout.forOrder(order.portfolioId(), order.id(),
                        order.payoutReason(), money, at), at),
                order::settled);
    }
}
