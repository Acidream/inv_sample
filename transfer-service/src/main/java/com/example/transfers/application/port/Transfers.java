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

    /** Transfers still waiting for their accounts that were requested before the given time. */
    List<Transfer> awaitingSince(Instant requestedBefore, int limit);
}
