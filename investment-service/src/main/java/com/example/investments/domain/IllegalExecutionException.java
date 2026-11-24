package com.example.investments.domain;

/**
 * A broker report that contradicts the order: a fill after the order ended, a fill beyond what was
 * ordered, an execution id reported twice with different details. A contract violation: never
 * retried, never silently ignored (ADR 0007).
 */
public class IllegalExecutionException extends RuntimeException {

    public IllegalExecutionException(String message) {
        super(message);
    }
}
