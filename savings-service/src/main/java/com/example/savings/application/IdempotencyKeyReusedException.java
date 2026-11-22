package com.example.savings.application;

/** The idempotency key was already used for a different request: a client bug, never retried. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used for a different request");
    }
}
