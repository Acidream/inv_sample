package com.example.investments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldingTest {

    private static final Instant AT = Instant.parse("2026-11-24T10:00:00Z");

    private final Holding holding = Holding.empty(UUID.randomUUID(), "DE0007164600");

    private void buy(String quantity, String cost) {
        var fill = new Fill("e" + quantity + cost, Quantity.of(quantity), Price.of("1"), new BigDecimal(cost), null,
                AT);
        holding.bought(Lot.bought(fill, "o"), Price.of("1"));
    }

    @Test
    void sold_AcrossTwoLots_TakesOldestFirstAndRealizesTheDifference() {
        buy("2", "200.00");
        buy("2", "300.00");
        holding.reserve(Quantity.of("3"));

        var sale = holding.sold(Quantity.of("3"), Price.of("160"), new BigDecimal("480.00"));

        assertThat(sale.costOfLotsSold()).isEqualByComparingTo("350");
        assertThat(sale.realizedGain()).isEqualTo(new BigDecimal("130.00"));
        assertThat(holding.quantity()).isEqualTo(Quantity.of("1"));
        assertThat(holding.costBasis()).isEqualTo(new BigDecimal("150.00"));
        assertThat(holding.reserved()).isEqualTo(Quantity.ZERO);
    }

    @Test
    void sold_InThirds_LeavesNoCostBehindOnceTheLotIsGone() {
        buy("3", "100.00");
        holding.reserve(Quantity.of("3"));

        var costs = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            costs = costs.add(holding.sold(Quantity.of("1"), Price.of("40"), new BigDecimal("40.00")).costOfLotsSold());
        }

        assertThat(costs).isEqualByComparingTo("100");
        assertThat(holding.costBasis()).isEqualByComparingTo("0");
        assertThat(holding.quantity().isZero()).isTrue();
    }

    @Test
    void reserve_MoreThanFree_ReservesNothing() {
        buy("2", "20.00");
        assertThat(holding.reserve(Quantity.of("1.5"))).isTrue();

        assertThat(holding.reserve(Quantity.of("0.500001"))).isFalse();
        assertThat(holding.free()).isEqualTo(Quantity.of("0.5"));
    }

    @Test
    void sold_UnitsNotReserved_ThrowsIllegalState() {
        buy("2", "20.00");
        assertThatThrownBy(() -> holding.sold(Quantity.of("1"), Price.of("10"), new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class);
    }
}
