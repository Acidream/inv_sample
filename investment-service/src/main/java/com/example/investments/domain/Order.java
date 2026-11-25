package com.example.investments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <p>What an order's fills cost is summed exactly ({@code quantity × price}, 10 fraction digits) and
 * rounded to the cent once, for the order: a fill's amount is what that rounded total grew by. So a
 * buy never pays more than its money, and a fill too small to be worth a cent moves no money.
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
    /** Exact value of fills: quantity (6 fraction digits) times price (4). */
    public static final int VALUE_SCALE = Quantity.SCALE + Price.SCALE;

    /** {@code PENDING} until the money is where it belongs; {@code UNPAID} if the current account refused it. */
    public enum Settlement { PENDING, SETTLED, UNPAID }

    public static final Money MINIMUM_BUY = Money.of("1.00");
    public static final String UNKNOWN_PORTFOLIO = "unknown portfolio";
    public static final String UNKNOWN_INSTRUMENT = "unknown instrument";
    public static final String NOT_ON_SALE = "instrument not on sale";
    public static final String NOT_ENOUGH_UNITS = "not enough free units";
    public static final String FUNDING_MISMATCH = "funding brought another amount than ordered";

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

    /**
     * Everything that is stored.
     *
     * @param filledValue what the fills cost exactly, {@link #VALUE_SCALE} fraction digits
     */
    public record State(Request request, Status status, Settlement settlement, LocalDate tradingDay,
            Quantity filledQuantity, BigDecimal filledValue, String rejectionReason) {
    }

    private final Request request;
    private Status status;
    private Settlement settlement;
    private LocalDate tradingDay;
    private Quantity filledQuantity;
    private BigDecimal filledValue;
    private String rejectionReason;

    /** Creating and restoring check the same invariants (ADR 0016). */
    private Order(State state) {
        this.request = Objects.requireNonNull(state.request(), "request");
        this.status = Objects.requireNonNull(state.status(), "status");
        this.settlement = Objects.requireNonNull(state.settlement(), "settlement");
        this.tradingDay = state.tradingDay();
        this.filledQuantity = Objects.requireNonNull(state.filledQuantity(), "filledQuantity");
        this.filledValue = Objects.requireNonNull(state.filledValue(), "filledValue")
                .setScale(VALUE_SCALE, RoundingMode.UNNECESSARY);
        this.rejectionReason = state.rejectionReason();
        checkInvariants();
    }

    /**
     * Only a buy waits for money. An order has a trading day once it was placed, so not while funding,
     * nor if its funding failed or it was refused at once; only a placed order can have fills. Settlement
     * waits for trading to end. Fills never exceed what was ordered.
     */
    private void checkInvariants() {
        var id = request.orderId();
        boolean waitsForMoney = status == Status.FUNDING || status == Status.FUNDING_FAILED;
        if (waitsForMoney && request.side() != Side.BUY) {
            throw new IllegalArgumentException("Order " + id + ": only a buy can be " + status);
        }
        boolean placed = tradingDay != null;
        if (waitsForMoney && placed || !waitsForMoney && status != Status.REJECTED && !placed) {
            throw new IllegalArgumentException("Order " + id + " is " + status
                    + (placed ? " but has trading day " + tradingDay : " without a trading day"));
        }
        if (!status.isFinal() && settlement != Settlement.PENDING) {
            throw new IllegalArgumentException("Order " + id + " is " + status + " and can't be " + settlement);
        }
        if ((status == Status.REJECTED || status == Status.FUNDING_FAILED) != (rejectionReason != null)) {
            throw new IllegalArgumentException("Order " + id + " is " + status
                    + (rejectionReason == null ? " without a reason" : " with a rejection reason"));
        }
        if (filledValue.signum() < 0 || filledQuantity.isZero() != (filledValue.signum() == 0)) {
            throw new IllegalArgumentException("Order " + id + " filled " + filledQuantity + " for " + filledValue);
        }
        boolean filling = status == Status.PARTIALLY_FILLED || status == Status.FILLED;
        if (filling && filledQuantity.isZero() || (!placed || status == Status.PLACED) && filledQuantity.isPositive()) {
            throw new IllegalArgumentException("Order " + id + " is " + status + " with " + filledQuantity
                    + " filled");
        }
        if (request.side() == Side.BUY && filledValue.compareTo(request.amount().amount()) > 0) {
            throw new IllegalArgumentException("Order " + id + " filled " + filledValue.toPlainString()
                    + ", beyond its " + request.amount());
        }
        if (request.side() == Side.SELL && filledQuantity.exceeds(request.quantity())) {
            throw new IllegalArgumentException("Order " + id + " filled " + filledQuantity + " units, beyond its "
                    + request.quantity());
        }
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
     * @return the fill, with the money it moves: what the order's rounded total grew by, maybe zero
     * @throws IllegalExecutionException if the fill contradicts the order
     */
    public Fill filled(Execution execution, boolean done) {
        requireTrading("be filled");
        var units = filledQuantity.plus(execution.quantity());
        var value = filledValue.add(execution.value());
        if (request.side() == Side.BUY && value.compareTo(request.amount().amount()) > 0) {
            throw new IllegalExecutionException("Fill " + execution.execId() + " takes order " + request.orderId()
                    + " to " + value.stripTrailingZeros().toPlainString() + ", beyond its " + request.amount());
        }
        if (request.side() == Side.SELL && units.exceeds(request.quantity())) {
            throw new IllegalExecutionException("Fill " + execution.execId() + " takes order " + request.orderId()
                    + " to " + units + " units, beyond its " + request.quantity());
        }
        var amount = cents(value).subtract(filledAmount());
        filledQuantity = units;
        filledValue = value;
        status = done ? Status.FILLED : Status.PARTIALLY_FILLED;
        return Fill.of(execution, amount);
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
     * or a sell's proceeds. Empty if nothing.
     */
    public Optional<Money> moneyToPayOut() {
        requireFinal("pay out");
        if (status == Status.FUNDING_FAILED) {
            return Optional.empty();
        }
        var money = request.side() == Side.BUY ? request.amount().amount().subtract(filledAmount()) : filledAmount();
        return money.signum() == 0 ? Optional.empty() : Optional.of(new Money(money));
    }

    /** Why the money from {@link #moneyToPayOut} goes to the current account. */
    public Payout.Reason payoutReason() {
        return request.side() == Side.BUY ? Payout.Reason.REFUND : Payout.Reason.PROCEEDS;
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

    private static BigDecimal cents(BigDecimal value) {
        return value.setScale(Money.SCALE, RoundingMode.HALF_EVEN);
    }

    public State state() {
        return new State(request, status, settlement, tradingDay, filledQuantity, filledValue, rejectionReason);
    }

    public String id() {
        return request.orderId();
    }

    public UUID portfolioId() {
        return request.portfolioId();
    }

    public String isin() {
        return request.isin();
    }

    public Side side() {
        return request.side();
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

    /** What the fills cost, rounded half-even to the cent: the money that moved. */
    public BigDecimal filledAmount() {
        return cents(filledValue);
    }

    /** What the fills cost exactly. */
    public BigDecimal filledValue() {
        return filledValue;
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }
}
