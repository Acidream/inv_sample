package com.example.investments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** One execution of an order as the broker reported it, before it is applied (ADR 0026, 0027). */
public record Execution(String execId, Quantity quantity, Price price, Instant tradedAt) {

    public Execution {
        Objects.requireNonNull(execId, "execId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(tradedAt, "tradedAt");
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("An execution has a positive quantity: " + quantity);
        }
    }

    /** What it cost exactly: quantity times price, with {@link Order#VALUE_SCALE} fraction digits, never rounded. */
    public BigDecimal value() {
        return quantity.value().multiply(price.value());
    }
}
