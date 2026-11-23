package com.example.transfers.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An account as the transfer service knows it: whose it is and which ledger holds it (ADR 0018).
 * Nothing about balances or products.
 */
public record KnownAccount(UUID accountId, UUID customerId, Ledger ledger) {

    public enum Ledger { SAVINGS, CURRENT_ACCOUNT }

    public KnownAccount {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(ledger, "ledger");
    }
}
