package com.example.currentaccount.application;

public class UnknownPaymentException extends RuntimeException {

    public UnknownPaymentException(String paymentId) {
        super("Incoming payment " + paymentId + " is not known (or has not been applied yet)");
    }
}
