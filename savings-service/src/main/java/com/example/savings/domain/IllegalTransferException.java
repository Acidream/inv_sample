package com.example.savings.domain;

/**
 * An instruction that contradicts what Savings already knows about a transfer, e.g. returning a
 * withdrawal that was rejected. A contract violation: never retried, never silently ignored.
 */
public class IllegalTransferException extends RuntimeException {

    public IllegalTransferException(String message) {
        super(message);
    }
}
