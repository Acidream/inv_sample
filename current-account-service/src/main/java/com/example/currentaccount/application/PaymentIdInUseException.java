package com.example.currentaccount.application;

/** The sender reused a payment id for a different account or amount. */
public class PaymentIdInUseException extends RuntimeException {

    public PaymentIdInUseException(String paymentId) {
        super("Incoming payment " + paymentId + " was already received with different details");
    }
}
