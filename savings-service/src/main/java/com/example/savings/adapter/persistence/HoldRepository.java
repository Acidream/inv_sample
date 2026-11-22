package com.example.savings.adapter.persistence;

import com.example.savings.application.UnknownWithdrawalException;
import com.example.savings.application.port.Holds;
import com.example.savings.domain.Hold;
import com.example.savings.domain.HoldStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HoldRepository extends JpaRepository<Hold, UUID>, Holds {

    @Override
    default void add(Hold hold) {
        save(hold);
    }

    @Override
    default Hold get(UUID id) {
        return findById(id).orElseThrow(() -> new UnknownWithdrawalException("hold " + id));
    }

    @Override
    default List<UUID> idsDueForExpiry(Instant now, int limit) {
        return findIdsByStatusExpiringBefore(HoldStatus.RESERVED, now, Limit.of(limit));
    }

    /** Served by the partial index hold_reserved_expiry_idx. */
    @Query("select h.id from Hold h where h.status = :status and h.expiresAt <= :now order by h.expiresAt")
    List<UUID> findIdsByStatusExpiringBefore(HoldStatus status, Instant now, Limit limit);
}
