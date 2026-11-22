package com.example.savings.adapter.persistence;

import com.example.savings.application.StatementCursor;
import com.example.savings.application.port.Ledger;
import com.example.savings.domain.LedgerEntry;
import com.example.savings.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link Ledger} on JPA: append-only, there is deliberately no update or delete. */
@Component
class JpaLedger implements Ledger {

    private final LedgerEntryJpaRepository repository;

    JpaLedger(LedgerEntryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(LedgerEntry entry) {
        if (entry.id() != null) {
            throw new IllegalArgumentException("Ledger entry " + entry.id() + " is already stored");
        }
        repository.save(LedgerEntryEntity.from(entry));
    }

    @Override
    public Money balanceAt(UUID accountId, Instant before) {
        return repository.findBalancesBefore(accountId, before, Limit.of(1)).stream()
                .findFirst()
                .map(Money::new)
                .orElse(Money.ZERO);
    }

    @Override
    public List<LedgerEntry> page(UUID accountId, StatementCursor after, int limit) {
        var entities = after == null
                ? repository.findByAccountIdOrderByBookedAtDescEntryNoDesc(accountId, Limit.of(limit))
                : repository.findOlderThan(accountId, after.bookedAt(), after.entryNo(), Limit.of(limit));
        return entities.stream().map(LedgerEntryEntity::toDomain).toList();
    }
}
