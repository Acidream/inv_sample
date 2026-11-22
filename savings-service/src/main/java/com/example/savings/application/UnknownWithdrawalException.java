package com.example.savings.application;

public class UnknownWithdrawalException extends RuntimeException {

    public UnknownWithdrawalException(String requestId) {
        super("Unknown withdrawal request " + requestId);
    }
}
