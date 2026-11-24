package com.example.fixgateway.application;

/** An order id sent again with a different order: a contract violation, dead-lettered (ADR 0007). */
public class OrderIdInUseException extends RuntimeException {

    public OrderIdInUseException(String orderId) {
        super("Order " + orderId + " was already placed with different details");
    }
}
