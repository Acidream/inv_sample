package com.example.transfers.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An account as the transfer service knows it: whose it is and which ledger holds it (ADR 0018).
 * Nothing about balances or products.
 */
public record KnownAccount(UUID accountId, UUID customerId, Ledger ledger) {

    /**
     * Which ledger holds the account. Savings and investments are product ledgers: they take the same
     * instructions ({@code payments.v1.PaymentInstruction}) and answer them the same way (ADR 0028).
     */
    public enum Ledger { SAVINGS, CURRENT_ACCOUNT, INVESTMENTS }

    public KnownAccount {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(ledger, "ledger");
    }
}
