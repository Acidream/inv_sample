package com.example.savings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Savings account aggregate. Every balance change goes through a method here and produces a
 * {@link LedgerEntry}; the cached {@code balance} always equals the {@code balanceAfter} of the
 * latest entry. {@code @Version} turns concurrent modifications into optimistic-lock failures
 * instead of lost updates.
 *
 * <p>The account also numbers its entries ({@code entryNo} 1, 2, 3, ...) and never books one earlier
 * than the previous one. Bookings can come from different instances whose clocks differ slightly;
 * without this, a later entry could get an earlier {@code bookedAt} and sort before the entry whose
 * balance it builds on.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    /** Decimal fraction per year, e.g. 0.0250 = 2.5 % p.a. */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal annualRate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    /** Interest earned but not yet paid out, sub-cent precision. */
    @Column(nullable = false, precision = 19, scale = Interest.ACCRUAL_SCALE)
    private BigDecimal accruedInterest;

    /** Last calendar day (business time zone) for which interest has been accrued. */
    @Column(nullable = false)
    private LocalDate lastAccrualDate;

    @Column(nullable = false, updatable = false)
    private Instant openedAt;

    /** Number of the latest ledger entry; 0 before the first booking. */
    @Column(nullable = false)
    private long lastEntryNo;

    /** {@code bookedAt} of the latest ledger entry ({@code openedAt} before the first booking). */
    @Column(nullable = false)
    private Instant lastBookedAt;

    @Version
    private Long version;

    protected Account() {
    }

    public static Account open(UUID customerId, BigDecimal annualRate, Instant now, ZoneId zone) {
        Objects.requireNonNull(customerId, "customerId");
        if (annualRate.signum() < 0) {
            throw new IllegalArgumentException("Annual rate must not be negative: " + annualRate);
        }
        var account = new Account();
        account.id = UUID.randomUUID();
        account.customerId = customerId;
        account.annualRate = annualRate.setScale(4, RoundingMode.UNNECESSARY);
        account.balance = Money.ZERO.amount();
        account.accruedInterest = BigDecimal.ZERO.setScale(Interest.ACCRUAL_SCALE);
        account.lastAccrualDate = LocalDate.ofInstant(now, zone).minusDays(1);
        account.openedAt = now;
        account.lastEntryNo = 0;
        account.lastBookedAt = now;
        return account;
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
        return balance().compareTo(amount) >= 0;
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
        var interest = Interest.forOneDay(endOfDayBalance, annualRate);
        accruedInterest = accruedInterest.add(interest);
        lastAccrualDate = day;
        return interest;
    }

    /**
     * Credits accrued interest rounded to cents. The sub-cent remainder stays accrued and is paid
     * with a later period, so no fraction of a cent is ever lost or invented.
     */
    public Optional<LedgerEntry> payOutInterest(YearMonth period, Instant now) {
        var payout = Money.roundedFrom(accruedInterest);
        if (!payout.isPositive()) {
            return Optional.empty();
        }
        accruedInterest = accruedInterest.subtract(payout.amount());
        return Optional.of(book(EntryType.INTEREST, payout, "interest:" + period, now));
    }

    // --- state --------------------------------------------------------------------------------

    private LedgerEntry book(EntryType type, Money signedAmount, String reference, Instant now) {
        var newBalance = balance().plus(signedAmount);
        balance = newBalance.amount();
        // A clock behind the previous booker's must not book into the past.
        lastBookedAt = now.isAfter(lastBookedAt) ? now : lastBookedAt;
        lastEntryNo++;
        return new LedgerEntry(id, lastEntryNo, type, signedAmount, newBalance, reference, lastBookedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public BigDecimal annualRate() {
        return annualRate;
    }

    public Money balance() {
        return new Money(balance);
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
