package com.example.savings.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of euros with cent precision. Immutable. May be negative: a ledger entry's amount is
 * signed, so a debit is a negative amount (balances themselves never go negative, ADR 0015).
 * Only EUR exists in this service, so the currency is implicit.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final String CURRENCY = "EUR";
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("Money has at most " + SCALE + " fraction digits: " + amount);
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    /**
     * Rounds a high-precision amount (e.g. accrued interest) down to whole cents, toward zero: an
     * interest payout never exceeds what has been earned.
     */
    public static Money roundedDown(BigDecimal amount) {
        return new Money(amount.setScale(SCALE, RoundingMode.DOWN));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(amount.negate());
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public Money requirePositive() {
        if (!isPositive()) {
            throw new IllegalArgumentException("Amount must be positive: " + this);
        }
        return this;
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
