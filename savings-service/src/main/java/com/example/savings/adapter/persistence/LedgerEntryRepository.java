package com.example.savings.adapter.persistence;

import com.example.savings.application.StatementCursor;
import com.example.savings.application.port.Ledger;
import com.example.savings.domain.LedgerEntry;
import com.example.savings.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Extends the bare {@link Repository} on purpose: no delete or bulk update is exposed. Every query
 * below is served by the index (account_id, booked_at desc, id desc).
 */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long>, Ledger {

    LedgerEntry save(LedgerEntry entry);

    @Override
    default void append(LedgerEntry entry) {
        save(entry);
    }

    @Override
    default Money balanceAt(UUID accountId, Instant before) {
        return findBalancesBefore(accountId, before, Limit.of(1)).stream()
                .findFirst()
                .map(Money::new)
                .orElse(Money.ZERO);
    }

    @Override
    default List<LedgerEntry> page(UUID accountId, StatementCursor after, int limit) {
        return after == null
                ? findByAccountIdOrderByBookedAtDescIdDesc(accountId, Limit.of(limit))
                : findOlderThan(accountId, after.bookedAt(), after.entryId(), Limit.of(limit));
    }

    @Query("""
            select e.balanceAfter from LedgerEntry e
            where e.accountId = :accountId and e.bookedAt < :before
            order by e.bookedAt desc, e.id desc""")
    List<BigDecimal> findBalancesBefore(UUID accountId, Instant before, Limit limit);

    List<LedgerEntry> findByAccountIdOrderByBookedAtDescIdDesc(UUID accountId, Limit limit);

    /** Keyset pagination: constant cost per page, unlike OFFSET which scans all skipped rows. */
    @Query("""
            select e from LedgerEntry e
            where e.accountId = :accountId
              and (e.bookedAt < :bookedAt or (e.bookedAt = :bookedAt and e.id < :entryId))
            order by e.bookedAt desc, e.id desc""")
    List<LedgerEntry> findOlderThan(UUID accountId, Instant bookedAt, long entryId, Limit limit);
}
