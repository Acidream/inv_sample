package com.example.savings.adapter.messaging;

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

    /**
     * Refuses to run outside a transaction: a message without its business change is exactly the bug.
     *
     * @param key the Kafka message key: the account id, or the transfer id for transfer requests
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, String key, String type, byte[] payload) {
        jdbc.sql("""
                insert into outbox_event (message_key, topic, event_type, payload)
                values (:key, :topic, :type, :payload)""")
                .param("key", key)
                .param("topic", topic)
                .param("type", type)
                .param("payload", payload)
                .update();
    }
}
