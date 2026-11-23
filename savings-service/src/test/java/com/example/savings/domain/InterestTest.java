package com.example.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InterestTest {

    @ParameterizedTest(name = "{0} at {1} p.a. earns {2} per day")
    @CsvSource({
        "1000.00, 0.0365, 0.1000000000",
        "100.00,  0.0100, 0.0027397260",
        "0.01,    0.0250, 0.0000006849",
        "0.00,    0.0250, 0.0000000000",
        "-50.00,  0.0250, 0.0000000000",   // cannot happen (ADR 0015); would earn nothing, no debit interest
        "5000.00, 0.0000, 0.0000000000"
    })
    void dailyInterestIsAct365WithTenFractionDigits(String balance, String rate, String expected) {
        assertThat(Interest.forOneDay(Money.of(balance), new BigDecimal(rate))).isEqualTo(new BigDecimal(expected));
    }
}
