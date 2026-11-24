package com.example.investments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A market order for one instrument (ADR 0026). A buy names the money to spend, which is pulled from
 * the current account before the order goes to the broker; a sell names the units, reserved on the
 * holding. Trading and money are tracked apart: the trading {@link Status} ends when the broker says
 * so, the {@link Settlement} when the money left over (a refund) or brought in (proceeds) has reached
 * the current account.
 *
 * <pre>
 * BUY:  FUNDING --&gt; PLACED --&gt; PARTIALLY_FILLED --&gt; FILLED | EXPIRED | REJECTED
 *          |           \______________________________/^
 *          v
 *    FUNDING_FAILED
 * SELL: PLACED --&gt; PARTIALLY_FILLED --&gt; FILLED | EXPIRED | REJECTED       (or REJECTED at once)
 * </pre>
 */
public final class Order {

    public enum Side { BUY, SELL }

    public enum Status {
        FUNDING, PLACED, PARTIALLY_FILLED, FILLED, EXPIRED, REJECTED, FUNDING_FAILED;

        public boolean isFinal() {
            return FINAL.contains(this);
        }
    }

    private static final Set<Status> FINAL = EnumSet.of(Status.FILLED, Status.EXPIRED, Status.REJECTED,
            Status.FUNDING_FAILED);

    /** {@code PENDING} until the money is where it belongs; {@code UNPAID} if the current account refused it. */
    public enum Settlement { PENDING, SETTLED, UNPAID }

    public static final Money MINIMUM_BUY = Money.of("1.00");
    public static final String UNKNOWN_PORTFOLIO = "unknown portfolio";
    public static final String UNKNOWN_INSTRUMENT = "unknown instrument";
    public static final String NOT_ON_SALE = "instrument not on sale";
    public static final String NOT_ENOUGH_UNITS = "not enough free units";

    private static final String FUNDING_PREFIX = "inv-buy-";

    /**
     * What the customer asked for, under the order id derived from their idempotency key.
     *
     * @param amount   BUY only
     * @param quantity SELL only
     */
    public record Request(String orderId, UUID portfolioId, String isin, Side side, Money amount, Quantity quantity,
            Instant acceptedAt) {

        public Request {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(portfolioId, "portfolioId");
            Objects.requireNonNull(isin, "isin");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            if (side == Side.BUY && (amount == null || quantity != null)) {
                throw new IllegalArgumentException("A buy names an amount, not a quantity");
            }
            if (side == Side.SELL && (quantity == null || amount != null)) {
                throw new IllegalArgumentException("A sell names a quantity, not an amount");
            }
            if (side == Side.BUY && amount.isLessThan(MINIMUM_BUY)) {
                throw new IllegalArgumentException("A buy is at least " + MINIMUM_BUY + ": " + amount);
            }
            if (side == Side.SELL && !quantity.isPositive()) {
                throw new IllegalArgumentException("A sell needs a positive quantity: " + quantity);
            }
        }

        /** The same order asked for again: everything but the time it was sent. */
        public boolean sameOrderAs(Request other) {
            return orderId.equals(other.orderId) && portfolioId.equals(other.portfolioId) && isin.equals(other.isin)
                    && side == other.side && Objects.equals(amount, other.amount)
                    && Objects.equals(quantity, other.quantity);
        }
    }

    /** Everything that is stored. */
    public record State(Request request, Status status, Settlement settlement, LocalDate tradingDay,
            Quantity filledQuantity, BigDecimal filledAmount, String rejectionReason) {
    }

    private final Request request;
    private Status status;
    private Settlement settlement;
    private LocalDate tradingDay;
    private Quantity filledQuantity;
    private BigDecimal filledAmount;
    private String rejectionReason;

    private Order(State state) {
        this.request = Objects.requireNonNull(state.request(), "request");
        this.status = Objects.requireNonNull(state.status(), "status");
        this.settlement = Objects.requireNonNull(state.settlement(), "settlement");
        this.tradingDay = state.tradingDay();
        this.filledQuantity = Objects.requireNonNull(state.filledQuantity(), "filledQuantity");
        this.filledAmount = Objects.requireNonNull(state.filledAmount(), "filledAmount").setScale(Money.SCALE);
        this.rejectionReason = state.rejectionReason();
    }

    public static Order restore(State state) {
        return new Order(state);
    }

    /** A buy, waiting for its money from the current account. */
    public static Order buy(Request request) {
        requireSide(request, Side.BUY);
        return new Order(new State(request, Status.FUNDING, Settlement.PENDING, null, Quantity.ZERO,
                BigDecimal.ZERO, null));
    }

    /** A sell whose units were reserved, placed with the broker for the given trading day. */
    public static Order sell(Request request, LocalDate tradingDay) {
        requireSide(request, Side.SELL);
        Objects.requireNonNull(tradingDay, "tradingDay");
        return new Order(new State(request, Status.PLACED, Settlement.PENDING, tradingDay, Quantity.ZERO,
                BigDecimal.ZERO, null));
    }

    /** Refused before anything moved or traded: nothing to settle. */
    public static Order rejected(Request request, String reason) {
        Objects.requireNonNull(reason, "reason");
        return new Order(new State(request, Status.REJECTED, Settlement.SETTLED, null, Quantity.ZERO,
                BigDecimal.ZERO, reason));
    }

    /** The transfer that brings a buy's money: its id is derived from the order's. */
    public static String fundingTransferId(String orderId) {
        return FUNDING_PREFIX + orderId;
    }

    /** @return the order id, if this is a buy's funding transfer */
    public static Optional<String> orderOfFundingTransfer(String transferId) {
        return transferId.startsWith(FUNDING_PREFIX)
                ? Optional.of(transferId.substring(FUNDING_PREFIX.length()))
                : Optional.empty();
    }

    // --- funding (buys) ---------------------------------------------------------------------------

    /** The money arrived: the order goes to the broker for this trading day. */
    public void funded(LocalDate day) {
        require(Status.FUNDING, "be funded");
        status = Status.PLACED;
        tradingDay = Objects.requireNonNull(day, "day");
    }

    /** True if the money that arrived under this order's funding transfer is what it waits for. */
    public boolean awaitsFunding(Money amount) {
        return status == Status.FUNDING && request.amount().equals(amount);
    }

    /** The current account refused the debit, or the transfer was invalid: nothing moved. */
    public void fundingFailed(String detail) {
        require(Status.FUNDING, "fail its funding");
        status = Status.FUNDING_FAILED;
        settlement = Settlement.SETTLED;
        rejectionReason = detail;
    }

    // --- broker reports ---------------------------------------------------------------------------

    /**
     * Records a fill. The broker never fills a buy for more money, or a sell for more units, than
     * ordered.
     *
     * @param done nothing more will be filled
     * @throws IllegalExecutionException if the fill contradicts the order
     */
    public void filled(Fill fill, boolean done) {
        requireTrading("be filled");
        var quantity = filledQuantity.plus(fill.quantity());
        var amount = filledAmount.add(fill.amount().amount());
        if (request.side() == Side.BUY && amount.compareTo(request.amount().amount()) > 0) {
            throw new IllegalExecutionException("Fill " + fill.execId() + " takes order " + request.orderId()
                    + " to " + amount + ", beyond its " + request.amount());
        }
        if (request.side() == Side.SELL && quantity.exceeds(request.quantity())) {
            throw new IllegalExecutionException("Fill " + fill.execId() + " takes order " + request.orderId()
                    + " to " + quantity + " units, beyond its " + request.quantity());
        }
        filledQuantity = quantity;
        filledAmount = amount;
        status = done ? Status.FILLED : Status.PARTIALLY_FILLED;
    }

    /** @return false if the order had already expired (a report sent again) */
    public boolean expired() {
        if (status == Status.EXPIRED) {
            return false;
        }
        requireTrading("expire");
        status = Status.EXPIRED;
        return true;
    }

    /** @return false if the broker had already rejected it (a report sent again) */
    public boolean rejectedByBroker(String reason) {
        if (status == Status.REJECTED) {
            return false;
        }
        requireTrading("be rejected");
        status = Status.REJECTED;
        rejectionReason = reason;
        return true;
    }

    // --- settlement -------------------------------------------------------------------------------

    /**
     * What has to go to the current account once trading has ended: the unspent part of a buy's money,
     * or a sell's proceeds. Zero if nothing.
     */
    public BigDecimal moneyToPayOut() {
        requireFinal("pay out");
        if (status == Status.FUNDING_FAILED) {
            return BigDecimal.ZERO.setScale(Money.SCALE);
        }
        return request.side() == Side.BUY ? request.amount().amount().subtract(filledAmount) : filledAmount;
    }

    /** Units a sell reserved and didn't sell, to release once trading has ended. */
    public Quantity unsoldUnits() {
        requireFinal("release units");
        return request.side() == Side.SELL ? request.quantity().minus(filledQuantity) : Quantity.ZERO;
    }

    public void settled() {
        requireFinal("be settled");
        settlement = Settlement.SETTLED;
    }

    public void unpaid() {
        requireFinal("be unpaid");
        settlement = Settlement.UNPAID;
    }

    /** A payout that was refused is tried again. */
    public void payoutRetried() {
        requireFinal("retry its payout");
        settlement = Settlement.PENDING;
    }

    private void require(Status expected, String action) {
        if (status != expected) {
            throw new IllegalExecutionException("Order " + request.orderId() + " is " + status + " and can't "
                    + action);
        }
    }

    private void requireTrading(String action) {
        if (status != Status.PLACED && status != Status.PARTIALLY_FILLED) {
            throw new IllegalExecutionException("Order " + request.orderId() + " is " + status + " and can't "
                    + action);
        }
    }

    private void requireFinal(String action) {
        if (!status.isFinal()) {
            throw new IllegalStateException("Order " + request.orderId() + " is " + status + " and can't " + action);
        }
    }

    private static void requireSide(Request request, Side side) {
        if (request.side() != side) {
            throw new IllegalArgumentException("Order " + request.orderId() + " is a " + request.side());
        }
    }

    public State state() {
        return new State(request, status, settlement, tradingDay, filledQuantity, filledAmount, rejectionReason);
    }

    public String id() {
        return request.orderId();
    }

    public Request request() {
        return request;
    }

    public Status status() {
        return status;
    }

    public Settlement settlement() {
        return settlement;
    }

    public Optional<LocalDate> tradingDay() {
        return Optional.ofNullable(tradingDay);
    }

    public Quantity filledQuantity() {
        return filledQuantity;
    }

    public BigDecimal filledAmount() {
        return filledAmount;
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }
}
