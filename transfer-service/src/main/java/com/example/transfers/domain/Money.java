package com.example.transfers.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A positive amount of euros with cent precision. A copy of savings' value object, not a shared one
 * (ADR 0018): a technical library is extracted when a third service needs it.
 */
public record Money(BigDecimal amount) {

    public static final int SCALE = 2;
    public static final String CURRENCY = "EUR";

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("Money has at most " + SCALE + " fraction digits: " + amount);
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A transfer amount must be positive: " + amount);
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
