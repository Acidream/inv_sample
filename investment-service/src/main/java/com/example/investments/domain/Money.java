package com.example.investments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A positive amount of euros with cent precision: what moves in one booking. A copy of the other
 * services' value object, not a shared one (ADR 0018, 0022). Balances may be zero and are plain
 * {@link BigDecimal}s.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final String CURRENCY = "EUR";

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("Money has at most " + SCALE + " fraction digits: " + amount);
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("An amount must be positive: " + amount);
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    /** Rounds half-even to the cent, as a fill's amount is (ADR 0026). */
    public static Money rounded(BigDecimal amount) {
        return new Money(amount.setScale(SCALE, RoundingMode.HALF_EVEN));
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
