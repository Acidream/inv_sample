package com.example.investments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a portfolio holds of one instrument: its lots, oldest first, and the quantity open sells have
 * reserved (ADR 0026). A sell may only take the free quantity, so two concurrent sells can't sell the
 * same units; its fills consume lots first in, first out.
 */
public final class Holding {

    /** What one sell fill realised. */
    public record Sale(BigDecimal costOfLotsSold, BigDecimal realizedGain) {
    }

    private final UUID portfolioId;
    private final String isin;
    private final List<Lot> lots;
    private Quantity reserved;
    private Price lastPrice;

    private Holding(UUID portfolioId, String isin, List<Lot> lots, Quantity reserved, Price lastPrice) {
        this.portfolioId = Objects.requireNonNull(portfolioId, "portfolioId");
        this.isin = Objects.requireNonNull(isin, "isin");
        this.lots = new ArrayList<>(lots);
        this.reserved = Objects.requireNonNull(reserved, "reserved");
        this.lastPrice = lastPrice;
        if (reserved.exceeds(quantity())) {
            throw new IllegalArgumentException("Holding " + portfolioId + "/" + isin + " reserves " + reserved
                    + " of " + quantity());
        }
    }

    public static Holding empty(UUID portfolioId, String isin) {
        return new Holding(portfolioId, isin, List.of(), Quantity.ZERO, null);
    }

    /** @param lots the lots with units left, oldest first */
    public static Holding restore(UUID portfolioId, String isin, List<Lot> lots, Quantity reserved, Price lastPrice) {
        return new Holding(portfolioId, isin, lots, reserved, lastPrice);
    }

    /** Units not reserved by open sells. */
    public Quantity free() {
        return quantity().minus(reserved);
    }

    /** @return false if not that much is free; nothing is reserved then */
    public boolean reserve(Quantity units) {
        if (units.exceeds(free())) {
            return false;
        }
        reserved = reserved.plus(units);
        return true;
    }

    /** Gives back what a sell reserved and didn't sell (it expired, was rejected or filled less). */
    public void release(Quantity units) {
        reserved = reserved.minus(units);
    }

    public void bought(Lot lot, Price price) {
        lots.add(lot);
        lastPrice = price;
    }

    /**
     * Sells reserved units, oldest lots first.
     *
     * @param proceeds what the fill brought in
     */
    public Sale sold(Quantity units, Price price, Money proceeds) {
        if (units.exceeds(reserved)) {
            throw new IllegalStateException("Selling " + units + " of " + isin + " but only " + reserved
                    + " are reserved");
        }
        var left = units;
        var cost = BigDecimal.ZERO;
        for (int i = 0; i < lots.size() && left.isPositive(); i++) {
            var lot = lots.get(i);
            var taken = left.min(lot.remaining());
            if (taken.isZero()) {
                continue;
            }
            cost = cost.add(lot.costOf(taken));
            lots.set(i, lot.without(taken));
            left = left.minus(taken);
        }
        reserved = reserved.minus(units);
        lastPrice = price;
        var realized = proceeds.amount().subtract(cost).setScale(Money.SCALE, RoundingMode.HALF_EVEN);
        return new Sale(cost, realized);
    }

    public Quantity quantity() {
        return lots.stream().map(Lot::remaining).reduce(Quantity.ZERO, Quantity::plus);
    }

    /** What the units held cost, by their lots, rounded half-even to the cent. */
    public BigDecimal costBasis() {
        return lots.stream().map(Lot::remainingCost).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(Money.SCALE, RoundingMode.HALF_EVEN);
    }

    /** All lots as they are now, including ones emptied by this change, for storage. */
    public List<Lot> lots() {
        return List.copyOf(lots);
    }

    public UUID portfolioId() {
        return portfolioId;
    }

    public String isin() {
        return isin;
    }

    public Quantity reserved() {
        return reserved;
    }

    public Optional<Price> lastPrice() {
        return Optional.ofNullable(lastPrice);
    }
}
