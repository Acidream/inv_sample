package com.example.investments.application;

import com.example.investments.application.port.Holdings;
import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Portfolios;
import com.example.investments.application.port.ProcessedMessages;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Fill;
import com.example.investments.domain.IllegalExecutionException;
import com.example.investments.domain.Lot;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Order.Side;
import com.example.investments.domain.Payout;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the broker reports about orders (ADR 0026, 0027). A buy fill pays the broker from the money
 * the order brought and adds a lot; a sell fill takes lots first in, first out and books the
 * proceeds. When an order ends, whatever money is left over or came in is paid out to the reference
 * account, and a sell's unsold units are released.
 */
@Service
public class Executions {

    private static final Logger log = LoggerFactory.getLogger(Executions.class);

    private final Portfolios portfolios;
    private final Orders orders;
    private final Holdings holdings;
    private final ProcessedMessages processed;
    private final Books books;
    private final PayoutDesk desk;

    Executions(Portfolios portfolios, Orders orders, Holdings holdings, ProcessedMessages processed, Books books,
            PayoutDesk desk) {
        this.portfolios = portfolios;
        this.orders = orders;
        this.holdings = holdings;
        this.processed = processed;
        this.books = books;
        this.desk = desk;
    }

    /**
     * @throws IllegalExecutionException if the report contradicts the order (dead-lettered): an
     *                                   unknown order, a fill after the end or beyond what was ordered
     */
    @Transactional
    public void apply(BrokerReport report) {
        if (!processed.markProcessed("execution:" + report.messageId())) {
            log.info("Skipping already applied broker report {}", report.messageId());
            return;
        }
        var portfolioId = orders.find(report.orderId())
                .orElseThrow(() -> new IllegalExecutionException("Broker reported on unknown order "
                        + report.orderId()))
                .request().portfolioId();
        var ledger = books.of(portfolios.lock(portfolioId).orElseThrow());
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
        var fill = Fill.of(report.execId(), report.quantity(), report.price(), report.at());
        var earlier = orders.findFill(order.id(), report.execId());
        if (earlier.isPresent()) {
            if (!earlier.get().sameExecutionAs(fill)) {
                throw new IllegalExecutionException("Execution " + report.execId() + " of order " + order.id()
                        + " was reported before with other details");
            }
            log.info("Execution {} of order {} was already applied; skipping", report.execId(), order.id());
            return;
        }
        order.filled(fill, report.done());
        var request = order.request();
        var holding = holdings.get(request.portfolioId(), request.isin());
        var reference = order.id() + ":" + fill.execId();
        if (request.side() == Side.BUY) {
            holding.bought(Lot.bought(order.id(), fill.execId(), fill.quantity(), fill.amount(), fill.tradedAt()),
                    fill.price());
            ledger.debit(CashEntry.Type.BUY, fill.amount(), reference, order.id(),
                    new CashEntry.Trade(request.isin(), fill.quantity(), fill.price(), null), fill.tradedAt());
        } else {
            var sale = holding.sold(fill.quantity(), fill.price(), fill.amount());
            fill = fill.withRealizedGain(sale.realizedGain());
            ledger.credit(CashEntry.Type.SELL, fill.amount(), reference, order.id(),
                    new CashEntry.Trade(request.isin(), fill.quantity(), fill.price(), sale.realizedGain()),
                    fill.tradedAt());
        }
        holdings.save(holding);
        orders.addFill(order.id(), fill);
        if (order.status().isFinal()) {
            ended(ledger, order, report.at());
        }
    }

    /** Trading is over: release unsold units, pay out what is left over or came in. */
    private void ended(Books.Ledger ledger, Order order, Instant at) {
        var request = order.request();
        var unsold = order.unsoldUnits();
        if (unsold.isPositive()) {
            var holding = holdings.get(request.portfolioId(), request.isin());
            holding.release(unsold);
            holdings.save(holding);
        }
        var money = order.moneyToPayOut();
        if (money.signum() == 0) {
            order.settled();
            return;
        }
        var reason = request.side() == Side.BUY ? Payout.Reason.REFUND : Payout.Reason.PROCEEDS;
        desk.payOut(ledger.portfolio(), Payout.forOrder(request.portfolioId(), order.id(), reason, new Money(money),
                at), at);
    }
}
