package com.example.transfers.domain;

import java.util.UUID;

/**
 * What the saga does next, decided by {@link Transfer} and carried out by the application: an
 * instruction to one of the ledgers, the transfer's outcome, or nothing (waiting).
 */
public sealed interface Step {

    /** Waiting: for an account to become known, or for a ledger to answer. */
    record Wait() implements Step {
    }

    record DebitCurrentAccount(UUID accountId, String transferId, Money amount) implements Step {
    }

    record CreditCurrentAccount(UUID accountId, String transferId, Money amount) implements Step {
    }

    /** Savings' {@code TransferToCurrentAccount}: savings debits at once if the balance allows it. */
    record DebitSavings(UUID accountId, String transferId, Money amount) implements Step {
    }

    /** Savings' {@code TransferFromCurrentAccount}: the current account has been debited. */
    record CreditSavings(UUID accountId, String transferId, Money amount) implements Step {
    }

    /** Savings' {@code TransferReturned}: the current account refused the credit, savings credits it back. */
    record ReturnToSavings(UUID accountId, String transferId) implements Step {
    }

    record Completed(String transferId) implements Step {
    }

    record Failed(String transferId, Transfer.FailureReason reason, String detail) implements Step {
    }
}
