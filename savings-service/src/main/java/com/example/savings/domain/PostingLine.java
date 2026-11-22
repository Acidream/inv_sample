package com.example.savings.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One side of a posting. The amount is always positive; the side says which way it goes.
 * Up to 10 fraction digits, because daily interest accruals are fractions of a cent.
 */
public record PostingLine(BookAccount account, Side side, BigDecimal amount) {

    public static final int MAX_SCALE = 10;

    public enum Side {
        DEBIT,
        CREDIT
    }

    public PostingLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A posting line moves a positive amount: " + amount);
        }
        if (amount.stripTrailingZeros().scale() > MAX_SCALE) {
            throw new IllegalArgumentException("At most " + MAX_SCALE + " fraction digits: " + amount);
        }
    }

    public static PostingLine debit(BookAccount account, BigDecimal amount) {
        return new PostingLine(account, Side.DEBIT, amount);
    }

    public static PostingLine credit(BookAccount account, BigDecimal amount) {
        return new PostingLine(account, Side.CREDIT, amount);
    }

    /** Debits count positive, credits negative: the lines of a balanced posting sum to zero. */
    BigDecimal signedAmount() {
        return side == Side.DEBIT ? amount : amount.negate();
    }
}
