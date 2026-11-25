package com.example.investments.application;

import com.example.investments.application.port.InvestmentMetrics;
import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Payouts;
import com.example.investments.application.port.TransferRequests;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import com.example.investments.domain.Portfolio;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Sends money a portfolio owes to its reference account, and keeps an order's settlement in step with
 * its payout (ADR 0026). Callers hold the portfolio's lock.
 */
@Component
class PayoutDesk {

    private final Payouts payouts;
    private final Orders orders;
    private final TransferRequests transfers;
    private final InvestmentMetrics metrics;

    PayoutDesk(Payouts payouts, Orders orders, TransferRequests transfers, InvestmentMetrics metrics) {
        this.payouts = payouts;
        this.orders = orders;
        this.transfers = transfers;
        this.metrics = metrics;
    }

    void payOut(Portfolio portfolio, Payout payout, Instant at) {
        payouts.add(payout);
        request(portfolio, payout, at);
    }

    /** Another attempt for an unpaid payout, under a new transfer id. */
    void retry(Portfolio portfolio, Payout payout, Instant at) {
        payout.retry();
        payouts.update(payout);
        settleOrder(payout, Order::payoutRetried);
        request(portfolio, payout, at);
    }

    void paid(Payout payout) {
        payout.paid();
        payouts.update(payout);
        settleOrder(payout, Order::settled);
    }

    void unpaid(Payout payout, Instant at) {
        payout.unpaid(at);
        payouts.update(payout);
        settleOrder(payout, Order::unpaid);
        metrics.payoutUnpaid(payout.reason());
    }

    /** Moves the settlement of the payout's order, if it has one, along with the payout. */
    private void settleOrder(Payout payout, Consumer<Order> change) {
        payout.orderId().flatMap(orders::find).ifPresent(order -> {
            change.accept(order);
            orders.update(order);
        });
    }

    private void request(Portfolio portfolio, Payout payout, Instant at) {
        transfers.request(portfolio.payout(payout, at));
        metrics.payoutRequested(payout.reason(), payout.amount());
    }
}
