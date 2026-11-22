package com.example.savings.domain;

/** The other side of the withdrawal contract sent something that cannot happen. */
public class IllegalHoldTransitionException extends RuntimeException {

    public IllegalHoldTransitionException(String requestId, HoldStatus from, String action) {
        super("Cannot " + action + " withdrawal " + requestId + " in status " + from);
    }
}
