package com.example.savings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable booking on an account. The ledger is append-only: corrections are new entries,
 * never updates. {@code balanceAfter} makes every entry a self-contained audit record and lets
 * the balance at any point in time be read with a single indexed lookup.
 *
 * <p>{@code entryNo} numbers an account's entries without gaps, in booking order; {@code bookedAt}
 * never decreases along it (see {@link Account}). So ordering by (bookedAt, entryNo) is the booking
 * order. The database {@code id} is not: each instance reserves its own block of sequence values.
 *
 * <p>The table is range-partitioned by {@code booked_at}, so its primary key is (id, booked_at).
 * Hibernate only needs {@code id} to be unique, which the sequence guarantees.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_entry_seq")
    @SequenceGenerator(name = "ledger_entry_seq", sequenceName = "ledger_entry_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false)
    private long entryNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EntryType type;

    /** Signed: credits are positive, debits negative. */
    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    /** Business reference: payment id, withdrawal request id or interest period. */
    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private Instant bookedAt;

    protected LedgerEntry() {
    }

    LedgerEntry(UUID accountId, long entryNo, EntryType type, Money amount, Money balanceAfter, String reference,
            Instant bookedAt) {
        this.accountId = accountId;
        this.entryNo = entryNo;
        this.type = type;
        this.amount = amount.amount();
        this.balanceAfter = balanceAfter.amount();
        this.reference = reference;
        this.bookedAt = bookedAt;
    }

    public Long id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public long entryNo() {
        return entryNo;
    }

    public EntryType type() {
        return type;
    }

    public Money amount() {
        return new Money(amount);
    }

    public Money balanceAfter() {
        return new Money(balanceAfter);
    }

    public String reference() {
        return reference;
    }

    public Instant bookedAt() {
        return bookedAt;
    }
}
