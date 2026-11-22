package com.example.savings.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency keys of account-opening requests, so a client that retries {@code POST /accounts}
 * after a timeout gets the account it already opened instead of a second one.
 */
public interface AccountOpenRequests {

    /** The account opened under this key, if any. */
    Optional<UUID> find(String idempotencyKey);

    /**
     * Remembers the key. Must run in the transaction that opens the account.
     *
     * @throws com.example.savings.application.IdempotencyKeyInUseException if a concurrent request
     *         with the same key got there first
     */
    void record(String idempotencyKey, UUID accountId);
}
