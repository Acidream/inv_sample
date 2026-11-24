package com.example.investments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One execution of an order, as the broker reported it. Its amount is quantity times price, rounded
 * half-even to the cent: that is what moves (ADR 0026).
 *
 * @param realizedGain sells only, set once the lots sold are known
 */
public record Fill(String execId, Quantity quantity, Price price, Money amount, BigDecimal realizedGain,
        Instant tradedAt) {

    public Fill {
        Objects.requireNonNull(execId, "execId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(tradedAt, "tradedAt");
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("A fill has a positive quantity: " + quantity);
        }
    }

    public static Fill of(String execId, Quantity quantity, Price price, Instant tradedAt) {
        return new Fill(execId, quantity, price, price.times(quantity), null, tradedAt);
    }

    public Fill withRealizedGain(BigDecimal gain) {
        return new Fill(execId, quantity, price, amount, gain, tradedAt);
    }

    /** The same execution reported again: everything the broker sent. */
    public boolean sameExecutionAs(Fill other) {
        return execId.equals(other.execId) && quantity.equals(other.quantity) && price.equals(other.price)
                && tradedAt.equals(other.tradedAt);
    }

    public Optional<BigDecimal> gain() {
        return Optional.ofNullable(realizedGain);
    }
}
