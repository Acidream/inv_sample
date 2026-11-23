package com.example.transfers.domain;

/**
 * An outcome that doesn't fit the transfer's state, e.g. a credit reported for a transfer that was
 * never debited, or by the wrong ledger. A contract violation: never retried, never silently ignored.
 */
public class IllegalTransferStateException extends RuntimeException {

    public IllegalTransferStateException(String message) {
        super(message);
    }
}
