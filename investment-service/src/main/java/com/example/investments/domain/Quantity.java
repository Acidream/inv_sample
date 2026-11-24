package com.example.investments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A number of units of an instrument, fractional, with at most {@link #SCALE} fraction digits: what
 * the broker reports. Never rounded by this service (ADR 0026). Zero is allowed for what is left.
 */
public record Quantity(BigDecimal value) implements Comparable<Quantity> {

    public static final int SCALE = 6;
    public static final Quantity ZERO = new Quantity(BigDecimal.ZERO);

    public Quantity {
        Objects.requireNonNull(value, "value");
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("A quantity has at most " + SCALE + " fraction digits: " + value);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("A quantity can't be negative: " + value);
        }
        value = value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public Quantity plus(Quantity other) {
        return new Quantity(value.add(other.value));
    }

    /** @throws IllegalArgumentException if the result would be negative */
    public Quantity minus(Quantity other) {
        return new Quantity(value.subtract(other.value));
    }

    public Quantity min(Quantity other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public boolean exceeds(Quantity other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Quantity other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
