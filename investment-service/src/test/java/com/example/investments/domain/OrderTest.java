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

        order.filled(Fill.of("e1", Quantity.of("1.5"), Price.of("500"), AT), false);
        assertThat(order.status()).isEqualTo(Order.Status.PARTIALLY_FILLED);
        assertThat(order.expired()).isTrue();

        assertThat(order.moneyToPayOut()).isEqualTo(new BigDecimal("250.00"));
        assertThat(order.settlement()).isEqualTo(Order.Settlement.PENDING);
    }

    @Test
    void filled_BeyondTheOrdersMoney_ThrowsIllegalExecution() {
        var order = placedBuy("100.00");

        assertThatThrownBy(() -> order.filled(Fill.of("e1", Quantity.of("1"), Price.of("100.01"), AT), false))
                .isInstanceOf(IllegalExecutionException.class);
        assertThat(order.filledAmount()).isEqualByComparingTo("0");
    }

    @Test
    void filled_AfterExpiry_ThrowsIllegalExecution() {
        var order = placedBuy("100.00");
        order.expired();

        assertThatThrownBy(() -> order.filled(Fill.of("e1", Quantity.of("1"), Price.of("1"), AT), true))
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

        order.filled(Fill.of("s1", Quantity.of("3"), Price.of("160"), AT), true);

        assertThat(order.moneyToPayOut()).isEqualTo(new BigDecimal("480.00"));
        assertThat(order.unsoldUnits()).isEqualTo(Quantity.ZERO);
    }

    @Test
    void fundingFailed_Funding_SettlesWithNothingToPayOut() {
        var order = Order.buy(buyRequest("50.00"));

        order.fundingFailed("DEBIT_REJECTED");

        assertThat(order.settlement()).isEqualTo(Order.Settlement.SETTLED);
        assertThat(order.moneyToPayOut()).isEqualByComparingTo("0");
    }

    @Test
    void request_BuyBelowMinimum_ThrowsIllegalArgument() {
        assertThatThrownBy(() -> buyRequest("0.99")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void times_PriceTimesFractionalQuantity_RoundsHalfEvenToTheCent() {
        assertThat(Price.of("96").times(Quantity.of("2.083333"))).isEqualTo(Money.of("200.00"));
        assertThat(Price.of("0.5").times(Quantity.of("0.05"))).isEqualTo(Money.of("0.02"));
        assertThat(Price.of("0.5").times(Quantity.of("0.07"))).isEqualTo(Money.of("0.04"));
    }

    @Test
    void tradingDayOf_Saturday_IsTheFollowingMonday() {
        assertThat(TradingCalendar.tradingDayOf(Instant.parse("2026-11-28T12:00:00Z")))
                .isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(TradingCalendar.tradingDayOf(Instant.parse("2026-11-23T23:30:00Z")))
                .isEqualTo(LocalDate.parse("2026-11-24"));
    }
}
