package com.example.transfers.application.port;

/** Ids of messages already applied, so redeliveries change nothing. */
public interface ProcessedMessages {

    /**
     * Marks a message as applied, in the caller's transaction.
     *
     * @return false if it had been applied before
     */
    boolean markProcessed(String messageId);
}
