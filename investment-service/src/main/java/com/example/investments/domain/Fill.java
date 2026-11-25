package com.example.investments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One execution of an order, applied. Its amount is the money it moved: what the order's total,
 * rounded half-even to the cent, grew by (ADR 0026). Zero for a fill too small to be worth a cent.
 *
 * @param amount       cents, not negative
 * @param realizedGain sells only, set once the lots sold are known
 */
public record Fill(String execId, Quantity quantity, Price price, BigDecimal amount, BigDecimal realizedGain,
        Instant tradedAt) {

    public Fill {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(Money.SCALE, RoundingMode.UNNECESSARY);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("A fill can't move a negative amount: " + amount);
        }
        new Execution(execId, quantity, price, tradedAt);
    }

    static Fill of(Execution execution, BigDecimal amount) {
        return new Fill(execution.execId(), execution.quantity(), execution.price(), amount, null,
                execution.tradedAt());
    }

    public Fill withRealizedGain(BigDecimal gain) {
        return new Fill(execId, quantity, price, amount, gain, tradedAt);
    }

    public Execution execution() {
        return new Execution(execId, quantity, price, tradedAt);
    }

    /** False if the order's total, rounded to the cent, didn't grow with this fill. */
    public boolean movesMoney() {
        return amount.signum() > 0;
    }

    /** The same execution reported again: everything the broker sent. */
    public boolean reports(Execution reported) {
        return execution().equals(reported);
    }

    public Optional<BigDecimal> gain() {
        return Optional.ofNullable(realizedGain);
    }
}
