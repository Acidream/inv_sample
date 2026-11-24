package com.example.investments.application;

/** The idempotency key that names this order was used for a different order. */
public class OrderIdInUseException extends RuntimeException {

    public OrderIdInUseException(String orderId) {
        super("Order " + orderId + " was already placed with different details");
    }
}
