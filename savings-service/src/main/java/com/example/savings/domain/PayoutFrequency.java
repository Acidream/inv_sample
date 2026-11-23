package com.example.savings.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * How often a product credits accrued interest. Periods are calendar periods in the business time
 * zone, aligned to the calendar year: quarters start in January, April, July and October.
 */
public enum PayoutFrequency {
    MONTHLY(1),
    QUARTERLY(3),
    YEARLY(12);

    private final int months;

    PayoutFrequency(int months) {
        this.months = months;
    }

    /** The payout period the day belongs to. */
    public InterestPeriod periodContaining(LocalDate day) {
        var firstMonth = (day.getMonthValue() - 1) / months * months + 1;
        var start = LocalDate.of(day.getYear(), firstMonth, 1);
        return new InterestPeriod(label(start), start, start.plusMonths(months).minusDays(1));
    }

    /** True on the last day of a period: once that day is accrued, its interest is paid out. */
    public boolean endsPeriodOn(LocalDate day) {
        return periodContaining(day).end().equals(day);
    }

    private String label(LocalDate start) {
        return switch (this) {
            case MONTHLY -> YearMonth.from(start).toString();
            case QUARTERLY -> start.getYear() + "-Q" + ((start.getMonthValue() - 1) / 3 + 1);
            case YEARLY -> String.valueOf(start.getYear());
        };
    }
}
