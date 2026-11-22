package com.example.savings.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable booking on an account. The ledger is append-only: corrections are new entries,
 * never updates. {@code balanceAfter} makes every entry a self-contained audit record and lets
 * the balance at any point in time be read with a single indexed lookup.
 *
 * <p>{@code entryNo} numbers an account's entries without gaps, in booking order; {@code bookedAt}
 * never decreases along it (see {@link Account}). So ordering by (bookedAt, entryNo) is the booking
 * order. The stored {@code id} is not: each instance reserves its own block of sequence values.
 *
 * <p>Only an {@link Account} creates new entries. {@code id} is null until the entry is stored.
 */
public final class LedgerEntry {

    private final Long id;
    private final UUID accountId;
    private final long entryNo;
    private final EntryType type;
    /** Signed: credits are positive, debits negative. */
    private final Money amount;
    private final Money balanceAfter;
    /** Business reference: transfer id or interest period. */
    private final String reference;
    private final Instant bookedAt;

    LedgerEntry(Long id, UUID accountId, long entryNo, EntryType type, Money amount, Money balanceAfter,
            String reference, Instant bookedAt) {
        this.id = id;
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        if (entryNo < 1) {
            throw new IllegalArgumentException("Entry numbers start at 1: " + entryNo);
        }
        this.entryNo = entryNo;
        this.type = Objects.requireNonNull(type, "type");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.bookedAt = Objects.requireNonNull(bookedAt, "bookedAt");
    }

    /** Rebuilds a stored entry. */
    public static LedgerEntry restore(long id, UUID accountId, long entryNo, EntryType type, Money amount,
            Money balanceAfter, String reference, Instant bookedAt) {
        return new LedgerEntry(id, accountId, entryNo, type, amount, balanceAfter, reference, bookedAt);
    }

    /** Null until stored. */
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
        return amount;
    }

    public Money balanceAfter() {
        return balanceAfter;
    }

    public String reference() {
        return reference;
    }

    public Instant bookedAt() {
        return bookedAt;
    }
}
