package com.example.transfers.application;

/** A transfer id was sent again with a different transfer. */
public class TransferIdInUseException extends RuntimeException {

    public TransferIdInUseException(String transferId) {
        super("Transfer " + transferId + " was already requested with different details");
    }
}
