package com.example.transfers.application;

public class UnknownTransferException extends RuntimeException {

    public UnknownTransferException(String transferId) {
        super("Unknown transfer " + transferId);
    }
}
