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
    void normalisesToCents() {
        assertThat(Money.of("5")).hasToString("5.00");
        assertThat(Money.of("5.1")).isEqualTo(Money.of("5.10"));
        assertThat(Money.of("5.000")).isEqualTo(Money.of("5.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.005", "-0.0001"})
    void rejectsSubCentAmounts(String amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of(amount));
    }

    @ParameterizedTest(name = "{0} rounds to {1}")
    @CsvSource({
        "0.0849315060, 0.08",
        "0.125,        0.12",   // half-even: ties go to the even neighbour
        "0.135,        0.14",
        "0.1250000001, 0.13"
    })
    void roundsHalfEvenOnlyWhenAskedTo(String raw, String expected) {
        assertThat(Money.roundedFrom(new BigDecimal(raw))).isEqualTo(Money.of(expected));
    }

    @Test
    void arithmeticKeepsSign() {
        assertThat(Money.of("10.00").minus(Money.of("10.01"))).isEqualTo(Money.of("-0.01"));
        assertThat(Money.of("-0.01").isNegative()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> Money.ZERO.requirePositive());
    }
}
