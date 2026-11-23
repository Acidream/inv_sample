package com.example.transfers.application.port;

import com.example.transfers.domain.Transfer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transfer storage. A changed transfer must be passed to {@link #update} in the transaction that changed it. */
public interface Transfers {

    void add(Transfer transfer);

    Optional<Transfer> find(String transferId);

    /** @throws org.springframework.dao.OptimisticLockingFailureException if it was changed concurrently */
    void update(Transfer transfer);

    /** Transfers waiting for this account to become known. */
    List<Transfer> awaiting(UUID accountId);

    /**
     * Transfers still waiting for their accounts that this service received before the given time.
     * Counted from receipt, not from the requester's {@code requestedAt}: a request delivered late (a
     * backlog, a replay) still gets the full time for its accounts' opening events to arrive.
     */
    List<Transfer> awaitingSince(Instant receivedBefore, int limit);

    /**
     * How many transfers wait for a ledger's answer (DEBITING, CREDITING, RETURNING) and have done so
     * since before the given time.
     */
    long countInFlightSince(Instant changedBefore);
}
