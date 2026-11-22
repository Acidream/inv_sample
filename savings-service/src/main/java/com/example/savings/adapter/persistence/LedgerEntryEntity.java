package com.example.savings.adapter.persistence;

import com.example.savings.domain.EntryType;
import com.example.savings.domain.LedgerEntry;
import com.example.savings.domain.Money;
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
 * Table mapping of {@link LedgerEntry}; insert-only. The table is range-partitioned by
 * {@code booked_at}, so its primary key is (id, booked_at). Hibernate only needs {@code id} to be
 * unique, which the sequence guarantees.
 */
@Entity
@Table(name = "ledger_entry")
class LedgerEntryEntity {

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

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private Instant bookedAt;

    protected LedgerEntryEntity() {
    }

    static LedgerEntryEntity from(LedgerEntry entry) {
        var entity = new LedgerEntryEntity();
        entity.accountId = entry.accountId();
        entity.entryNo = entry.entryNo();
        entity.type = entry.type();
        entity.amount = entry.amount().amount();
        entity.balanceAfter = entry.balanceAfter().amount();
        entity.reference = entry.reference();
        entity.bookedAt = entry.bookedAt();
        return entity;
    }

    LedgerEntry toDomain() {
        return LedgerEntry.restore(id, accountId, entryNo, type, new Money(amount), new Money(balanceAfter), reference,
                bookedAt);
    }
}
