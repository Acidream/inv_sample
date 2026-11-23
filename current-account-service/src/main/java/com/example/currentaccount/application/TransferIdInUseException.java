package com.example.currentaccount.application;

/**
 * The transfer service sent an instruction under a transfer id that was already answered for another
 * direction or amount. A contract violation: never retried, never silently ignored.
 */
public class TransferIdInUseException extends RuntimeException {

    public TransferIdInUseException(String message) {
        super(message);
    }
}
