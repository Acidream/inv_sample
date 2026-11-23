package com.example.savings.adapter.persistence;

import com.example.platform.messaging.ProcessedMessages;
import com.example.savings.application.port.ProcessedEvents;
import org.springframework.stereotype.Component;

/** The port, backed by the shared idempotent consumer (ADR 0022). */
@Component
class JdbcProcessedEvents implements ProcessedEvents {

    private final ProcessedMessages processed;

    JdbcProcessedEvents(ProcessedMessages processed) {
        this.processed = processed;
    }

    @Override
    public boolean markProcessed(String eventId) {
        return processed.markProcessed(eventId);
    }
}
