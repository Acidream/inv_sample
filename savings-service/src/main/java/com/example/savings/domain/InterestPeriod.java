package com.example.savings.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The days whose interest one payout credits, both inclusive.
 *
 * @param label names the period in references and events: {@code 2026-01} (month), {@code 2026-Q1}
 *              (quarter) or {@code 2026} (year)
 */
public record InterestPeriod(String label, LocalDate start, LocalDate end) {

    public InterestPeriod {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Period " + label + " ends before it starts: " + start + " - " + end);
        }
    }
}
