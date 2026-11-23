package com.example.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PayoutFrequencyTest {

    @Test
    void periodContaining_Monthly_ReturnsCalendarMonth() {
        var february = PayoutFrequency.MONTHLY.periodContaining(LocalDate.of(2028, 2, 10));

        assertThat(february).isEqualTo(
                new InterestPeriod("2028-02", LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)));
    }

    @Test
    void periodContaining_Quarterly_StartsInJanuaryAprilJulyOrOctober() {
        assertThat(PayoutFrequency.QUARTERLY.periodContaining(LocalDate.of(2026, 1, 1))).isEqualTo(
                new InterestPeriod("2026-Q1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)));
        assertThat(PayoutFrequency.QUARTERLY.periodContaining(LocalDate.of(2026, 6, 30))).isEqualTo(
                new InterestPeriod("2026-Q2", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30)));
        assertThat(PayoutFrequency.QUARTERLY.periodContaining(LocalDate.of(2026, 11, 23))).isEqualTo(
                new InterestPeriod("2026-Q4", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)));
    }

    @Test
    void periodContaining_Yearly_ReturnsCalendarYear() {
        assertThat(PayoutFrequency.YEARLY.periodContaining(LocalDate.of(2026, 7, 15))).isEqualTo(
                new InterestPeriod("2026", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    @Test
    void endsPeriodOn_LastDayOfPeriod_IsTrue() {
        assertThat(PayoutFrequency.MONTHLY.endsPeriodOn(LocalDate.of(2026, 4, 30))).isTrue();
        assertThat(PayoutFrequency.MONTHLY.endsPeriodOn(LocalDate.of(2026, 4, 29))).isFalse();
        assertThat(PayoutFrequency.QUARTERLY.endsPeriodOn(LocalDate.of(2026, 4, 30))).isFalse();
        assertThat(PayoutFrequency.QUARTERLY.endsPeriodOn(LocalDate.of(2026, 9, 30))).isTrue();
        assertThat(PayoutFrequency.YEARLY.endsPeriodOn(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(PayoutFrequency.YEARLY.endsPeriodOn(LocalDate.of(2026, 12, 31))).isTrue();
    }
}
