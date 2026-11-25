package com.example.investments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant AT = Instant.parse("2026-11-24T10:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-11-24");

    private static Order.Request buyRequest(String amount) {
        return new Order.Request("ord-1", UUID.randomUUID(), "IE00B4L5Y983", Order.Side.BUY, Money.of(amount), null,
                AT);
    }

    private static Order placedBuy(String amount) {
        var order = Order.buy(buyRequest(amount));
        order.funded(DAY);
        return order;
    }

    @Test
    void filled_PartlyThenExpired_RefundsWhatWasNotSpent() {
        var order = placedBuy("1000.00");

        order.filled(new Execution("e1", Quantity.of("1.5"), Price.of("500"), AT), false);
        assertThat(order.status()).isEqualTo(Order.Status.PARTIALLY_FILLED);
        assertThat(order.expired()).isTrue();

        assertThat(order.moneyToPayOut()).contains(Money.of("250.00"));
        assertThat(order.settlement()).isEqualTo(Order.Settlement.PENDING);
    }

    @Test
    void filled_BeyondTheOrdersMoney_ThrowsIllegalExecution() {
        var order = placedBuy("100.00");

        assertThatThrownBy(() -> order.filled(new Execution("e1", Quantity.of("1"), Price.of("100.01"), AT), false))
                .isInstanceOf(IllegalExecutionException.class);
        assertThat(order.filledAmount()).isEqualByComparingTo("0");
    }

    @Test
    void filled_AfterExpiry_ThrowsIllegalExecution() {
        var order = placedBuy("100.00");
        order.expired();

        assertThatThrownBy(() -> order.filled(new Execution("e1", Quantity.of("1"), Price.of("1"), AT), true))
                .isInstanceOf(IllegalExecutionException.class);
    }

    @Test
    void expired_Twice_ChangesNothingTheSecondTime() {
        var order = placedBuy("100.00");

        assertThat(order.expired()).isTrue();
        assertThat(order.expired()).isFalse();
    }

    @Test
    void sell_FilledInFull_PaysOutItsProceedsAndReleasesNothing() {
        var request = new Order.Request("ord-2", UUID.randomUUID(), "DE0007164600", Order.Side.SELL, null,
                Quantity.of("3"), AT);
        var order = Order.sell(request, DAY);

        order.filled(new Execution("s1", Quantity.of("3"), Price.of("160"), AT), true);

        assertThat(order.moneyToPayOut()).contains(Money.of("480.00"));
        assertThat(order.unsoldUnits()).isEqualTo(Quantity.ZERO);
    }

    @Test
    void fundingFailed_Funding_SettlesWithNothingToPayOut() {
        var order = Order.buy(buyRequest("50.00"));

        order.fundingFailed("DEBIT_REJECTED");

        assertThat(order.settlement()).isEqualTo(Order.Settlement.SETTLED);
        assertThat(order.moneyToPayOut()).isEmpty();
    }

    @Test
    void request_BuyBelowMinimum_ThrowsIllegalArgument() {
        assertThatThrownBy(() -> buyRequest("0.99")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filled_FractionalQuantity_MovesTheValueRoundedHalfEvenToTheCent() {
        var order = placedBuy("500.00");

        var fill = order.filled(new Execution("e1", Quantity.of("2.083333"), Price.of("96"), AT), false);

        assertThat(fill.amount()).isEqualTo(new BigDecimal("200.00"));
        assertThat(order.filledValue()).isEqualByComparingTo("199.999968");
    }

    @Test
    void filled_WorthLessThanACent_MovesNoMoneyButCountsItsUnits() {
        var order = placedBuy("100.00");

        var fill = order.filled(new Execution("e1", Quantity.of("0.000001"), Price.of("96"), AT), false);

        assertThat(fill.amount()).isEqualByComparingTo("0");
        assertThat(fill.movesMoney()).isFalse();
        assertThat(order.filledQuantity()).isEqualTo(Quantity.of("0.000001"));
        assertThat(order.status()).isEqualTo(Order.Status.PARTIALLY_FILLED);
    }

    @Test
    void filled_FillsWhoseRoundedSumWouldExceedTheMoney_RoundTheOrdersTotalOnceAndStayWithin() {
        // Alone, 33.335 rounds half-even to 33.34: rounded per fill, these would come to 100.01, a cent
        // beyond the money. Their exact sum is 100.00, and that is what the order's total is.
        var order = placedBuy("100.00");

        var first = order.filled(new Execution("e1", Quantity.of("1"), Price.of("33.335"), AT), false);
        var second = order.filled(new Execution("e2", Quantity.of("1"), Price.of("33.335"), AT), false);
        var third = order.filled(new Execution("e3", Quantity.of("1"), Price.of("33.33"), AT), true);

        assertThat(first.amount().add(second.amount()).add(third.amount()))
                .isEqualByComparingTo(order.filledAmount());
        assertThat(order.filledAmount()).isEqualByComparingTo("100.00");
        assertThat(order.moneyToPayOut()).isEmpty();
    }

    @Test
    void restore_FilledBeyondTheOrdersMoney_ThrowsIllegalArgument() {
        var state = new Order.State(buyRequest("100.00"), Order.Status.FILLED, Order.Settlement.PENDING, DAY,
                Quantity.of("1"), new BigDecimal("100.01"), null);

        assertThatThrownBy(() -> Order.restore(state)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restore_PlacedWithoutTradingDay_ThrowsIllegalArgument() {
        var state = new Order.State(buyRequest("100.00"), Order.Status.PLACED, Order.Settlement.PENDING, null,
                Quantity.ZERO, BigDecimal.ZERO, null);

        assertThatThrownBy(() -> Order.restore(state)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restore_SettledWhileStillTrading_ThrowsIllegalArgument() {
        var state = new Order.State(buyRequest("100.00"), Order.Status.PARTIALLY_FILLED, Order.Settlement.SETTLED,
                DAY, Quantity.of("1"), new BigDecimal("50"), null);

        assertThatThrownBy(() -> Order.restore(state)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restore_StateOfAnOrderThatWasFilled_RestoresTheSameOrder() {
        var order = placedBuy("100.00");
        order.filled(new Execution("e1", Quantity.of("1.5"), Price.of("60"), AT), true);

        var restored = Order.restore(order.state());

        assertThat(restored.state()).isEqualTo(order.state());
        assertThat(restored.filledAmount()).isEqualByComparingTo("90.00");
    }

    @Test
    void tradingDayOf_Saturday_IsTheFollowingMonday() {
        assertThat(TradingCalendar.tradingDayOf(Instant.parse("2026-11-28T12:00:00Z")))
                .isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(TradingCalendar.tradingDayOf(Instant.parse("2026-11-23T23:30:00Z")))
                .isEqualTo(LocalDate.parse("2026-11-24"));
    }
}
