package com.example.savings.domain.order;

/**
 * An outcome that contradicts the order's state, e.g. a transfer reported completed after it was
 * reported failed. A contract violation for a person to look at, never retried.
 */
public class IllegalOrderTransitionException extends RuntimeException {

    public IllegalOrderTransitionException(String message) {
        super(message);
    }
}
