package com.example.investments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * The units one buy fill added to a holding, and what is left of them and of their cost. Sold first
 * in, first out (ADR 0026). The remaining cost keeps {@link #COST_SCALE} fraction digits, so selling
 * a lot in parts doesn't drift from what it cost.
 *
 * @param id null until stored
 */
public record Lot(Long id, String orderId, String execId, Quantity quantity, Quantity remaining,
        BigDecimal remainingCost, Instant acquiredAt) {

    public static final int COST_SCALE = 10;

    public Lot {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(execId, "execId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(remainingCost, "remainingCost");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        remainingCost = remainingCost.setScale(COST_SCALE, RoundingMode.UNNECESSARY);
        if (remaining.exceeds(quantity) || remainingCost.signum() < 0) {
            throw new IllegalArgumentException("Lot " + orderId + ":" + execId + " can't have " + remaining
                    + " of " + quantity + " left at a cost of " + remainingCost);
        }
    }

    /** The units a buy fill added, at the money it moved: maybe zero, for a fill worth less than a cent. */
    public static Lot bought(Fill fill, String orderId) {
        return new Lot(null, orderId, fill.execId(), fill.quantity(), fill.quantity(), fill.amount(),
                fill.tradedAt());
    }

    /** What taking {@code units} out of this lot takes of its cost; the whole rest when all is taken. */
    public BigDecimal costOf(Quantity units) {
        if (units.exceeds(remaining)) {
            throw new IllegalArgumentException("Lot holds " + remaining + ", can't take " + units);
        }
        if (units.equals(remaining)) {
            return remainingCost;
        }
        return remainingCost.multiply(units.value()).divide(remaining.value(), COST_SCALE, RoundingMode.HALF_EVEN);
    }

    public Lot without(Quantity units) {
        return new Lot(id, orderId, execId, quantity, remaining.minus(units), remainingCost.subtract(costOf(units)),
                acquiredAt);
    }

    public boolean isEmpty() {
        return remaining.isZero();
    }
}
