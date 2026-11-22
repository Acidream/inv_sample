package com.example.savings.adapter.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox, write side: a serialized message is inserted in the same transaction as the
 * business change. {@link OutboxRelay} ships it to Kafka afterwards. This avoids the dual-write
 * problem (DB committed but Kafka send lost, or the other way round).
 */
@Component
class Outbox {

    private final JdbcClient jdbc;

    Outbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Refuses to run outside a transaction: a message without its business change is exactly the bug. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, UUID key, String type, byte[] payload) {
        jdbc.sql("""
                insert into outbox_event (aggregate_id, topic, event_type, payload)
                values (:aggregateId, :topic, :type, :payload)""")
                .param("aggregateId", key)
                .param("topic", topic)
                .param("type", type)
                .param("payload", payload)
                .update();
    }
}
