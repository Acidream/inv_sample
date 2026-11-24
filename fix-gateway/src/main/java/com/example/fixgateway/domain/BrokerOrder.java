package com.example.fixgateway.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * An order the gateway took from {@code broker.orders.v1} and places with the broker over FIX
 * (ADR 0027). Placed once per order id: a redelivered or re-sent {@code PlaceOrder} changes nothing.
 *
 * <pre>
 * RECEIVED --&gt; SENDING --&gt; SENT --&gt; ACCEPTED --&gt; DONE
 *     |            |   ^      \__________________/^
 *     v            v   |
 *   DONE      (unknown at the broker after a restart: RECEIVED again)
 * </pre>
 *
 * <p>{@code SENDING} is stored before the message goes out, so after a crash the gateway knows the
 * message may or may not have left, and asks the broker instead of sending it twice.
 */
public final class BrokerOrder {

    public enum Side { BUY, SELL }

    public enum Status {
        RECEIVED, SENDING, SENT, ACCEPTED, DONE;

        /** Still expecting reports from the broker. */
        public boolean isWithBroker() {
            return this == SENDING || this == SENT || this == ACCEPTED;
        }
    }

    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /**
     * What investments asked for.
     *
     * @param cashAmount BUY: spend at most this many euros
     * @param quantity   SELL: this many units
     */
    public record Placement(String orderId, String isin, Side side, BigDecimal cashAmount, BigDecimal quantity,
            LocalDate tradingDay) {

        public Placement {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(isin, "isin");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(tradingDay, "tradingDay");
            if ((side == Side.BUY) != (cashAmount != null) || (side == Side.SELL) != (quantity != null)) {
                throw new IllegalArgumentException("A buy names a cash amount, a sell a quantity");
            }
        }

        /** The same order sent again: everything investments sent. */
        public boolean samePlacementAs(Placement other) {
            return orderId.equals(other.orderId) && isin.equals(other.isin) && side == other.side
                    && compare(cashAmount, other.cashAmount) && compare(quantity, other.quantity)
                    && tradingDay.equals(other.tradingDay);
        }

        private static boolean compare(BigDecimal a, BigDecimal b) {
            return a == null ? b == null : b != null && a.compareTo(b) == 0;
        }
    }

    private final Placement placement;
    private final Instant receivedAt;
    private Status status;

    private BrokerOrder(Placement placement, Instant receivedAt, Status status) {
        this.placement = Objects.requireNonNull(placement, "placement");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static BrokerOrder received(Placement placement, Instant at) {
        return new BrokerOrder(placement, at, Status.RECEIVED);
    }

    public static BrokerOrder restore(Placement placement, Instant receivedAt, Status status) {
        return new BrokerOrder(placement, receivedAt, status);
    }

    /** A market order is good for its trading day only: it ends at midnight Berlin. */
    public boolean tradingDayEndedAt(Instant now) {
        return !now.isBefore(placement.tradingDay().plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    /** About to go out; stored before it does. */
    public void sending() {
        require(Status.RECEIVED, "be sent");
        status = Status.SENDING;
    }

    public void sent() {
        require(Status.SENDING, "be marked sent");
        status = Status.SENT;
    }

    /** The broker confirmed it has the order. @return false if it was already known to be there */
    public boolean accepted() {
        if (status == Status.SENDING || status == Status.SENT) {
            status = Status.ACCEPTED;
            return true;
        }
        return false;
    }

    /** The broker doesn't know an order whose sending was interrupted: it never left, send it again. */
    public void neverArrived() {
        require(Status.SENDING, "be sent again");
        status = Status.RECEIVED;
    }

    /** Filled in full, rejected or expired: nothing more will come. */
    public void done() {
        status = Status.DONE;
    }

    public boolean isDone() {
        return status == Status.DONE;
    }

    private void require(Status expected, String action) {
        if (status != expected) {
            throw new IllegalStateException("Order " + placement.orderId() + " is " + status + " and can't " + action);
        }
    }

    public String orderId() {
        return placement.orderId();
    }

    public Placement placement() {
        return placement;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Status status() {
        return status;
    }

    public Optional<BigDecimal> cashAmount() {
        return Optional.ofNullable(placement.cashAmount());
    }
}
