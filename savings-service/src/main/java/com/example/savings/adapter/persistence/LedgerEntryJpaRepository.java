package com.example.savings.adapter.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Extends the bare {@link Repository} on purpose: no delete or bulk update is exposed. Every query
 * below is served by the index (account_id, booked_at desc, entry_no desc). Entries are ordered by
 * (bookedAt, entryNo), which is the booking order; never by the database id.
 */
interface LedgerEntryJpaRepository extends Repository<LedgerEntryEntity, Long> {

    LedgerEntryEntity save(LedgerEntryEntity entry);

    @Query("""
            select e.balanceAfter from LedgerEntryEntity e
            where e.accountId = :accountId and e.bookedAt < :before
            order by e.bookedAt desc, e.entryNo desc""")
    List<BigDecimal> findBalancesBefore(UUID accountId, Instant before, Limit limit);

    List<LedgerEntryEntity> findByAccountIdOrderByBookedAtDescEntryNoDesc(UUID accountId, Limit limit);

    /** Keyset pagination: constant cost per page, unlike OFFSET which scans all skipped rows. */
    @Query("""
            select e from LedgerEntryEntity e
            where e.accountId = :accountId
              and (e.bookedAt < :bookedAt or (e.bookedAt = :bookedAt and e.entryNo < :entryNo))
            order by e.bookedAt desc, e.entryNo desc""")
    List<LedgerEntryEntity> findOlderThan(UUID accountId, Instant bookedAt, long entryNo, Limit limit);
}
