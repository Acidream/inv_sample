package com.example.currentaccount.adapter.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox, write side, as in the other services (ADR 0006): a message is inserted in the same
 * transaction as the state change; {@link OutboxRelay} ships it to Kafka afterwards.
 */
@Component
class Outbox {

    private final JdbcClient jdbc;

    Outbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Refuses to run outside a transaction: a message without its state change is exactly the bug.
     *
     * @param key the Kafka message key: the account id
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
