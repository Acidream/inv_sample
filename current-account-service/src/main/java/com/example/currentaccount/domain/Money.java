package com.example.currentaccount.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A positive amount of euros with cent precision: what moves in one booking. A copy of the other
 * services' value object, not a shared one (ADR 0018, 0021). Balances may be negative and are plain
 * {@link BigDecimal}s.
 */
public record Money(BigDecimal amount) {

    public static final int SCALE = 2;
    public static final String CURRENCY = "EUR";
    /**
     * The largest balance the ledger can hold: {@code numeric(19, 2)}. A booking that would go beyond
     * it is refused, rather than failing at the database and being retried without end (ADR 0007).
     */
    public static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");

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

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
