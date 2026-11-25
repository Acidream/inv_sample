package com.example.investments.application.port;

import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import java.math.BigDecimal;

/** Business metrics, kept behind a port so use cases don't depend on a metrics library (ADR 0013). */
public interface InvestmentMetrics {

    /** An order was taken from the command log: placed, waiting for its money, or refused at once. */
    void orderReceived(Order order);

    /** @param amount the money the fill moved, maybe zero */
    void filled(Order.Side side, BigDecimal amount);

    /** Trading ended: filled, expired, rejected, or the money never came. */
    void orderEnded(Order order);

    void payoutRequested(Payout.Reason reason, Money amount);

    /** The current account refused a payout; the money rests on the portfolio. */
    void payoutUnpaid(Payout.Reason reason);

    /** A redelivered message or a re-sent instruction the idempotent consumer skipped. */
    void duplicateMessage();
}
