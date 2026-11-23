package com.example.platform.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer (ADR 0007): remembers the ids of messages already applied, in table
 * {@code processed_message}. The marker is written in the caller's transaction, so if the business
 * change rolls back, so does the marker, and the redelivery is applied.
 *
 * <p>Message ids from different sources may collide; callers prefix them with their source, e.g.
 * {@code "command:" + id}.
 */
public class ProcessedMessages {

    private final JdbcClient jdbc;

    public ProcessedMessages(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true the first time an id is seen, false for a duplicate */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String messageId) {
        return jdbc.sql("insert into processed_message (message_id) values (:id) on conflict do nothing")
                .param("id", messageId)
                .update() == 1;
    }
}
