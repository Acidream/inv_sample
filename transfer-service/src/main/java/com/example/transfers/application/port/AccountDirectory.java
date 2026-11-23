package com.example.transfers.application.port;

import com.example.transfers.domain.KnownAccount;
import java.util.Optional;
import java.util.UUID;

/**
 * The accounts the transfer service knows, built from the ledgers' opening events (ADR 0018). Never
 * filled by asking another service.
 */
public interface AccountDirectory {

    /**
     * Serializes, until the end of the current transaction, everything done about these accounts.
     * A request and the opening event of one of its accounts are consumed concurrently from different
     * topics: without the lock each could miss the other's uncommitted work and leave the transfer
     * waiting for good.
     */
    void lock(UUID... accountIds);

    /** Remembers a new account. The caller checks first whether the id is known (ADR 0023). */
    void add(KnownAccount account);

    Optional<KnownAccount> find(UUID accountId);
}
