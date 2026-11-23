package com.example.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void of_FewerFractionDigits_NormalisesToCents() {
        assertThat(Money.of("5")).hasToString("5.00");
        assertThat(Money.of("5.1")).isEqualTo(Money.of("5.10"));
        assertThat(Money.of("5.000")).isEqualTo(Money.of("5.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.005", "-0.0001"})
    void of_SubCentAmount_Throws(String amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of(amount));
    }

    @ParameterizedTest(name = "{0} rounds down to {1}")
    @CsvSource({
        "0.0849315060, 0.08",
        "0.125,        0.12",
        "0.135,        0.13",
        "0.0099999999, 0.00",   // never a cent that hasn't been fully earned
        "0.1999950000, 0.19"
    })
    void roundedDown_SubCentAmount_RoundsTowardZero(String raw, String expected) {
        assertThat(Money.roundedDown(new BigDecimal(raw))).isEqualTo(Money.of(expected));
    }

    @Test
    void plusMinusNegate_SignedAmounts_KeepSign() {
        assertThat(Money.of("10.00").minus(Money.of("10.01"))).isEqualTo(Money.of("-0.01"));
        assertThat(Money.of("-0.01").isNegative()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> Money.ZERO.requirePositive());
    }
}
