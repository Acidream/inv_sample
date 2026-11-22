package com.example.savings.application;

public class UnknownTransferException extends RuntimeException {

    public UnknownTransferException(String transferId) {
        super("Unknown transfer " + transferId);
    }
}
