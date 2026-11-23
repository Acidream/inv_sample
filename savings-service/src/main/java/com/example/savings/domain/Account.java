package com.example.savings.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Savings account aggregate. Every balance change goes through a method here and produces a
 * {@link LedgerEntry}; the cached {@code balance} always equals the {@code balanceAfter} of the
 * latest entry.
 *
 * <p>The account also numbers its entries ({@code entryNo} 1, 2, 3, ...) and never books one earlier
 * than the previous one. Bookings can come from different instances whose clocks differ slightly;
 * without this, a later entry could get an earlier {@code bookedAt} and sort before the entry whose
 * balance it builds on.
 *
 * <p>Its terms come from the {@link SavingsProduct} version it was opened on, kept for the account's
 * whole life (ADR 0017).
 *
 * <p>Plain Java: persistence maps it through {@link State} (ADR 0016). {@code version} is the
 * optimistic-concurrency token of the stored aggregate (null until first stored); the domain never
 * interprets it, persistence refuses to store a change made to an outdated version.
 */
public final class Account {

    /** Everything that is stored, for persistence to map; invariants are checked on {@link #restore}. */
    public record State(UUID id, UUID customerId, SavingsProduct product, Money balance, BigDecimal accruedInterest,
            LocalDate lastAccrualDate, Instant openedAt, long lastEntryNo, Instant lastBookedAt, Long version) {
    }

    private final UUID id;
    private final UUID customerId;
    private final SavingsProduct product;
    private final Instant openedAt;
    private final Long version;
    private Money balance;
    /** Interest earned but not yet paid out, sub-cent precision. */
    private BigDecimal accruedInterest;
    /** Last calendar day (business time zone) for which interest has been accrued. */
    private LocalDate lastAccrualDate;
    /** Number of the latest ledger entry; 0 before the first booking. */
    private long lastEntryNo;
    /** {@code bookedAt} of the latest ledger entry ({@code openedAt} before the first booking). */
    private Instant lastBookedAt;

    private Account(State state) {
        this.id = Objects.requireNonNull(state.id(), "id");
        this.customerId = Objects.requireNonNull(state.customerId(), "customerId");
        this.product = Objects.requireNonNull(state.product(), "product");
        this.balance = Objects.requireNonNull(state.balance(), "balance");
        if (balance.isNegative()) {
            throw new IllegalArgumentException("Balance must not be negative: " + balance);
        }
        this.accruedInterest = state.accruedInterest().setScale(Interest.ACCRUAL_SCALE, RoundingMode.UNNECESSARY);
        this.lastAccrualDate = Objects.requireNonNull(state.lastAccrualDate(), "lastAccrualDate");
        this.openedAt = Objects.requireNonNull(state.openedAt(), "openedAt");
        this.lastEntryNo = state.lastEntryNo();
        this.lastBookedAt = Objects.requireNonNull(state.lastBookedAt(), "lastBookedAt");
        this.version = state.version();
    }

    /** @param id chosen by the caller, so the same order opens the same account on every replay (ADR 0019) */
    public static Account open(UUID id, UUID customerId, SavingsProduct product, Instant now, ZoneId zone) {
        return new Account(new State(id, customerId, product, Money.ZERO, BigDecimal.ZERO,
                LocalDate.ofInstant(now, zone).minusDays(1), now, 0, now, null));
    }

    /** Rebuilds a stored account. */
    public static Account restore(State state) {
        return new Account(state);
    }

    public State state() {
        return new State(id, customerId, product, balance, accruedInterest, lastAccrualDate, openedAt, lastEntryNo,
                lastBookedAt, version);
    }

    // --- money in -----------------------------------------------------------------------------

    public LedgerEntry deposit(Money amount, String transferId, Instant now) {
        return book(EntryType.DEPOSIT, amount.requirePositive(), transferId, now);
    }

    /** A withdrawal the current account refused comes back. */
    public LedgerEntry creditReturnedWithdrawal(Money amount, String transferId, Instant now) {
        return book(EntryType.WITHDRAWAL_RETURN, amount.requirePositive(), transferId, now);
    }

    // --- money out ----------------------------------------------------------------------------

    public boolean canWithdraw(Money amount) {
        return balance.compareTo(amount) >= 0;
    }

    /**
     * Debits at once: money only ever goes to the customer's own current account, an internal
     * transfer with a known outcome, so nothing has to be reserved while waiting for the outside world.
     * The balance can never go negative.
     */
    public LedgerEntry withdraw(Money amount, String transferId, Instant now) {
        amount.requirePositive();
        if (!canWithdraw(amount)) {
            throw new IllegalStateException("Insufficient balance on account " + id + " for " + amount);
        }
        return book(EntryType.WITHDRAWAL, amount.negate(), transferId, now);
    }

    // --- interest -----------------------------------------------------------------------------

    public LocalDate nextAccrualDate() {
        return lastAccrualDate.plusDays(1);
    }

    /** Accrues one day of interest. Days must be accrued in order, without gaps. */
    public BigDecimal accrueDay(LocalDate day, Money endOfDayBalance) {
        if (!day.equals(nextAccrualDate())) {
            throw new IllegalStateException("Expected accrual for " + nextAccrualDate() + " but got " + day);
        }
        var interest = Interest.forOneDay(endOfDayBalance, product.annualRate());
        accruedInterest = accruedInterest.add(interest);
        lastAccrualDate = day;
        return interest;
    }

    /** True if the day is the last of a payout period of the account's product. */
    public boolean payoutDueAfter(LocalDate day) {
        return product.payoutFrequency().endsPeriodOn(day);
    }

    /** The payout period of the account's product that the day belongs to. */
    public InterestPeriod payoutPeriodOf(LocalDate day) {
        return product.payoutFrequency().periodContaining(day);
    }

    /**
     * Credits accrued interest rounded down to whole cents. The sub-cent remainder (0 to 0.0099...)
     * stays accrued and is paid with a later period, so no fraction of a cent is ever lost or invented,
     * and none is paid before it has been earned: accrued interest never goes negative.
     */
    public Optional<LedgerEntry> payOutInterest(InterestPeriod period, Instant now) {
        var payout = Money.roundedDown(accruedInterest);
        if (!payout.isPositive()) {
            return Optional.empty();
        }
        accruedInterest = accruedInterest.subtract(payout.amount());
        return Optional.of(book(EntryType.INTEREST, payout, "interest:" + period.label(), now));
    }

    // --- state --------------------------------------------------------------------------------

    private LedgerEntry book(EntryType type, Money signedAmount, String reference, Instant now) {
        balance = balance.plus(signedAmount);
        // A clock behind the previous booker's must not book into the past.
        lastBookedAt = now.isAfter(lastBookedAt) ? now : lastBookedAt;
        lastEntryNo++;
        return new LedgerEntry(null, id, lastEntryNo, type, signedAmount, balance, reference, lastBookedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public SavingsProduct product() {
        return product;
    }

    public BigDecimal annualRate() {
        return product.annualRate();
    }

    public Money balance() {
        return balance;
    }

    public BigDecimal accruedInterest() {
        return accruedInterest;
    }

    public LocalDate lastAccrualDate() {
        return lastAccrualDate;
    }

    public Instant openedAt() {
        return openedAt;
    }
}
