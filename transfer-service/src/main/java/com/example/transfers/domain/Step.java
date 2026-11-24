package com.example.transfers.domain;

import com.example.transfers.domain.KnownAccount.Ledger;
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

    /**
     * A product ledger's {@code TransferToCurrentAccount}: savings or investments debits at once if it
     * can, or rejects.
     */
    record DebitProductLedger(Ledger ledger, UUID accountId, String transferId, Money amount) implements Step {
    }

    /** A product ledger's {@code TransferFromCurrentAccount}: the current account has been debited. */
    record CreditProductLedger(Ledger ledger, UUID accountId, String transferId, Money amount) implements Step {
    }

    /**
     * A product ledger's {@code TransferReturned}: the current account refused the credit, the product
     * ledger credits it back.
     */
    record ReturnToProductLedger(Ledger ledger, UUID accountId, String transferId) implements Step {
    }

    record Completed(String transferId) implements Step {
    }

    record Failed(String transferId, Transfer.FailureReason reason, String detail) implements Step {
    }
}
