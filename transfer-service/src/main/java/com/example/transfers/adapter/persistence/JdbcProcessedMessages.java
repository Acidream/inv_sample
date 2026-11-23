package com.example.transfers.adapter.persistence;

import com.example.transfers.application.port.ProcessedMessages;
import org.springframework.stereotype.Component;

/** The port, backed by the shared idempotent consumer (ADR 0022). */
@Component
class JdbcProcessedMessages implements ProcessedMessages {

    private final com.example.platform.messaging.ProcessedMessages processed;

    JdbcProcessedMessages(com.example.platform.messaging.ProcessedMessages processed) {
        this.processed = processed;
    }

    @Override
    public boolean markProcessed(String messageId) {
        return processed.markProcessed(messageId);
    }
}
