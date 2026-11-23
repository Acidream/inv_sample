package com.example.platform.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox, write side (ADR 0006): a serialized message is inserted in the same
 * transaction as the business change, and {@link OutboxRelay} ships it to Kafka afterwards. This
 * avoids the dual write (database committed but the Kafka send lost, or the other way round).
 *
 * <p>Needs the table {@code outbox_event} described in {@code package-info}.
 */
public class Outbox {

    private final JdbcClient jdbc;

    public Outbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Refuses to run outside a transaction: a message without its business change is exactly the bug.
     *
     * @param key  the Kafka message key; messages with one key keep their order
     * @param type a label for people reading the table, e.g. the payload's case
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
