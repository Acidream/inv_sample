package com.example.investments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One booking on a portfolio's money ledger: append-only, numbered 1, 2, 3, ... per portfolio, with
 * the balance after it (ADR 0004, 0026). The portfolio holds money only while an order is in flight,
 * or as an unpaid payout.
 *
 * @param amount    signed: negative for money leaving the portfolio
 * @param reference the transfer id, or the fill ({@code <order id>:<exec id>})
 * @param trade     the fill this entry pays for or comes from; empty for transfers
 */
public record CashEntry(UUID portfolioId, long entryNo, Type type, BigDecimal amount, BigDecimal balanceAfter,
        String reference, String orderId, Trade trade, Instant bookedAt) {

    public enum Type {
        /** Money arrived from the current account. */
        FUNDING(true),
        /** A buy fill: money paid to the broker. */
        BUY(false),
        /** A sell fill: money received from the broker. */
        SELL(true),
        /** Money left for the current account: a refund or proceeds. */
        PAYOUT(false),
        /** The current account refused a payout; the money is back, as an unpaid payout. */
        PAYOUT_RETURN(true);

        private final boolean credit;

        Type(boolean credit) {
            this.credit = credit;
        }

        public boolean isCredit() {
            return credit;
        }
    }

    /** @param realizedGain sells only: proceeds minus the FIFO cost of the lots sold, may be negative */
    public record Trade(String isin, Quantity quantity, Price price, BigDecimal realizedGain) {

        public Trade {
            Objects.requireNonNull(isin, "isin");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(price, "price");
        }
    }

    /**
     * What is about to be booked; the portfolio numbers it and signs the amount by its type.
     *
     * @param orderId the order the money is for, if any
     */
    public record Booking(Type type, Money amount, String reference, String orderId, Trade trade, Instant at) {

        public Booking {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(at, "at");
            if ((type == Type.BUY || type == Type.SELL) != (trade != null)) {
                throw new IllegalArgumentException(type + (trade == null ? " needs" : " has no") + " trade details");
            }
        }

        /** Money from the current account, under the given transfer. */
        public static Booking funding(String transferId, Money amount, String orderId, Instant at) {
            return new Booking(Type.FUNDING, amount, transferId, orderId, null, at);
        }

        /** Money leaving for the current account under the payout's transfer. */
        public static Booking payout(String transferId, Money amount, String orderId, Instant at) {
            return new Booking(Type.PAYOUT, amount, transferId, orderId, null, at);
        }

        /** A payout the current account refused, back on the portfolio. */
        public static Booking payoutReturned(String transferId, Money amount, String orderId, Instant at) {
            return new Booking(Type.PAYOUT_RETURN, amount, transferId, orderId, null, at);
        }

        /** What a fill moved: paid to the broker for a buy, received from it for a sell. */
        public static Booking fill(Order order, Fill fill) {
            var buy = order.side() == Order.Side.BUY;
            return new Booking(buy ? Type.BUY : Type.SELL, new Money(fill.amount()), order.id() + ":" + fill.execId(),
                    order.id(), new Trade(order.isin(), fill.quantity(), fill.price(), fill.realizedGain()),
                    fill.tradedAt());
        }
    }

    public CashEntry {
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(bookedAt, "bookedAt");
        if ((amount.signum() > 0) != type.isCredit() || amount.signum() == 0) {
            throw new IllegalArgumentException(type + " can't be booked as " + amount);
        }
    }

    public Optional<Trade> tradeDetails() {
        return Optional.ofNullable(trade);
    }

    /** The unsigned amount that moved. */
    public Money money() {
        return new Money(amount.abs());
    }
}
