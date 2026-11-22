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

    /** Sum of all RESERVED holds. available = balance - heldAmount. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal heldAmount;

    /** Interest earned but not yet paid out, sub-cent precision. */
    @Column(nullable = false, precision = 19, scale = Interest.ACCRUAL_SCALE)
    private BigDecimal accruedInterest;

    /** Last calendar day (business time zone) for which interest has been accrued. */
    @Column(nullable = false)
    private LocalDate lastAccrualDate;

    @Column(nullable = false, updatable = false)
    private Instant openedAt;

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
        account.heldAmount = Money.ZERO.amount();
        account.accruedInterest = BigDecimal.ZERO.setScale(Interest.ACCRUAL_SCALE);
        account.lastAccrualDate = LocalDate.ofInstant(now, zone).minusDays(1);
        account.openedAt = now;
        return account;
    }

    // --- money in -----------------------------------------------------------------------------

    public LedgerEntry deposit(Money amount, String paymentId, Instant now) {
        return book(EntryType.DEPOSIT, amount.requirePositive(), paymentId, now);
    }

    // --- money out (two-phase) ----------------------------------------------------------------

    public boolean canReserve(Money amount) {
        return availableBalance().compareTo(amount) >= 0;
    }

    /** Phase 1: earmark funds. They still belong to the customer (and still earn interest). */
    public void reserve(Money amount) {
        amount.requirePositive();
        if (!canReserve(amount)) {
            throw new IllegalStateException("Insufficient available balance on account " + id);
        }
        heldAmount = heldAmount().plus(amount).amount();
    }

    /** Phase 2b or expiry: give earmarked funds back. */
    public void releaseHold(Money amount) {
        heldAmount = heldAmount().minus(amount).amount();
    }

    /** Phase 2a: the transfer happened, turn the hold into a real debit. */
    public LedgerEntry settleHold(Money amount, String requestId, Instant now) {
        releaseHold(amount);
        return book(EntryType.WITHDRAWAL, amount.negate(), requestId, now);
    }

    /**
     * A confirmation arrived after the hold had already expired. The money has left the bank, so
     * it is booked unconditionally, even if that overdraws the account.
     */
    public LedgerEntry debitAfterExpiredHold(Money amount, String requestId, Instant now) {
        return book(EntryType.WITHDRAWAL, amount.negate(), requestId, now);
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
        return new LedgerEntry(id, type, signedAmount, newBalance, reference, now);
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

    public Money heldAmount() {
        return new Money(heldAmount);
    }

    public Money availableBalance() {
        return balance().minus(heldAmount());
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
