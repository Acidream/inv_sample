package com.example.savings.application;

/** Another request with the same idempotency key is being processed right now; retrying is safe. */
public class IdempotencyKeyInUseException extends RuntimeException {

    public IdempotencyKeyInUseException(String idempotencyKey) {
        super("A request with Idempotency-Key " + idempotencyKey + " is already being processed; retry it");
    }
}
