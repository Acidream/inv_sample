package com.example.investments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.investments.application.port.InvestmentMetrics;
import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Payouts;
import com.example.investments.application.port.TransferRequests;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import com.example.investments.domain.Portfolio;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** An order's settlement follows its payout (ADR 0026). */
class PayoutDeskTest {

    private static final Instant AT = Instant.parse("2026-11-24T10:00:00Z");

    private final Payouts payouts = mock(Payouts.class);
    private final Orders orders = mock(Orders.class);
    private final TransferRequests transfers = mock(TransferRequests.class);
    private final InvestmentMetrics metrics = mock(InvestmentMetrics.class);
    private final PayoutDesk desk = new PayoutDesk(payouts, orders, transfers, metrics);

    private final Portfolio portfolio = Portfolio.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            AT, new BigDecimal("40.00"), 3);

    private Order expiredBuy() {
        var order = Order.buy(new Order.Request("ord-1", portfolio.id(), "IE00B4L5Y983", Order.Side.BUY,
                Money.of("40.00"), null, AT));
        order.funded(LocalDate.parse("2026-11-24"));
        order.expired();
        when(orders.find(order.id())).thenReturn(Optional.of(order));
        return order;
    }

    private Payout refund(Order order) {
        return Payout.forOrder(portfolio.id(), order.id(), Payout.Reason.REFUND, Money.of("40.00"), AT);
    }

    @Test
    void paid_RefundOfAnOrder_SettlesTheOrder() {
        var order = expiredBuy();
        var payout = refund(order);

        desk.paid(payout);

        assertThat(order.settlement()).isEqualTo(Order.Settlement.SETTLED);
        verify(orders).update(order);
    }

    @Test
    void retry_UnpaidRefund_PutsTheOrderBackToPendingAndRequestsTheNextAttempt() {
        var order = expiredBuy();
        var payout = refund(order);
        desk.unpaid(payout, AT);

        desk.retry(portfolio, payout, AT.plusSeconds(3600));

        assertThat(order.settlement()).isEqualTo(Order.Settlement.PENDING);
        verify(transfers).request(portfolio.payout(payout, AT.plusSeconds(3600)));
        assertThat(payout.transferId()).endsWith("-2");
    }

    @Test
    void unpaid_UnexpectedDeposit_CountsTheRefusalWithoutAnOrder() {
        var payout = Payout.unexpectedDeposit(portfolio.id(), "t-9", Money.of("5.00"), AT);

        desk.unpaid(payout, AT);

        assertThat(payout.status()).isEqualTo(Payout.Status.UNPAID);
        verify(metrics).payoutUnpaid(Payout.Reason.UNEXPECTED_DEPOSIT);
    }
}
