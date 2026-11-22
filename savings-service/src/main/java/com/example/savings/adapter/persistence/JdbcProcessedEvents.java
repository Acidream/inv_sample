package com.example.savings.adapter.persistence;

import com.example.savings.application.port.ProcessedEvents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcProcessedEvents implements ProcessedEvents {

    private final JdbcClient jdbc;

    JdbcProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs in the caller's transaction: if the business change rolls back, so does the marker. */
    @Override
    public boolean markProcessed(String eventId) {
        return jdbc.sql("insert into processed_event (event_id) values (:id) on conflict do nothing")
                .param("id", eventId)
                .update() == 1;
    }
}
