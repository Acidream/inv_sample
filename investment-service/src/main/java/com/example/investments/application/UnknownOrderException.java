package com.example.investments.application;

/** Also the answer until an accepted order has been applied: the client polls. */
public class UnknownOrderException extends RuntimeException {

    public UnknownOrderException(String orderId) {
        super("Unknown order " + orderId);
    }
}
