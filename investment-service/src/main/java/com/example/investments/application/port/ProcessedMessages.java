package com.example.investments.application.port;

/** Idempotent consumer (ADR 0007): ids of messages already applied. */
public interface ProcessedMessages {

    /** @return false if the message was processed before */
    boolean markProcessed(String messageId);
}
