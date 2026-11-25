package com.example.investments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Euros per unit of an instrument, positive, at most {@link #SCALE} fraction digits. */
public record Price(BigDecimal value) {

    public static final int SCALE = 4;

    public Price {
        Objects.requireNonNull(value, "value");
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("A price has at most " + SCALE + " fraction digits: " + value);
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("A price must be positive: " + value);
        }
        value = value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Price of(String value) {
        return new Price(new BigDecimal(value));
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
