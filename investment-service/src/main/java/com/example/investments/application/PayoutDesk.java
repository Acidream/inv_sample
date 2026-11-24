package com.example.investments.application;

import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Outbound;
import com.example.investments.application.port.Payouts;
import com.example.investments.domain.Payout;
import com.example.investments.domain.Portfolio;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Sends money a portfolio owes to its reference account, and keeps an order's settlement in step with
 * its payout (ADR 0026). Callers hold the portfolio's lock.
 */
@Component
class PayoutDesk {

    private final Payouts payouts;
    private final Orders orders;
    private final Outbound outbound;

    PayoutDesk(Payouts payouts, Orders orders, Outbound outbound) {
        this.payouts = payouts;
        this.orders = orders;
        this.outbound = outbound;
    }

    void payOut(Portfolio portfolio, Payout payout, Instant at) {
        payouts.add(payout);
        request(portfolio, payout, at);
    }

    /** Another attempt for an unpaid payout, under a new transfer id. */
    void retry(Portfolio portfolio, Payout payout, Instant at) {
        payout.retry();
        payouts.update(payout);
        payout.orderId().flatMap(orders::find).ifPresent(order -> {
            order.payoutRetried();
            orders.update(order);
        });
        request(portfolio, payout, at);
    }

    void paid(Payout payout) {
        payout.paid();
        payouts.update(payout);
        payout.orderId().flatMap(orders::find).ifPresent(order -> {
            order.settled();
            orders.update(order);
        });
    }

    void unpaid(Payout payout, Instant at) {
        payout.unpaid(at);
        payouts.update(payout);
        payout.orderId().flatMap(orders::find).ifPresent(order -> {
            order.unpaid();
            orders.update(order);
        });
    }

    private void request(Portfolio portfolio, Payout payout, Instant at) {
        outbound.requestTransfer(payout.transferId(), portfolio.customerId(), portfolio.id(),
                portfolio.referenceAccountId(), payout.amount(), at);
    }
}
