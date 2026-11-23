package com.example.transfers.adapter.persistence;

import com.example.transfers.application.port.ProcessedMessages;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcProcessedMessages implements ProcessedMessages {

    private final JdbcClient jdbc;

    JdbcProcessedMessages(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs in the caller's transaction: if the change rolls back, so does the marker. */
    @Override
    public boolean markProcessed(String messageId) {
        return jdbc.sql("insert into processed_message (message_id) values (:id) on conflict do nothing")
                .param("id", messageId)
                .update() == 1;
    }
}
