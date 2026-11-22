package com.example.savings.adapter.messaging;

import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.config.SavingsProperties;
import com.example.savings.domain.AccountEvent;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox, write side: the serialized event is inserted in the same transaction as the
 * business change. {@link OutboxRelay} ships it to Kafka afterwards. This avoids the dual-write
 * problem (DB committed but Kafka send lost, or the other way round).
 */
@Component
class OutboxAccountEventPublisher implements AccountEventPublisher {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String topic;

    OutboxAccountEventPublisher(JdbcClient jdbc, Clock clock, SavingsProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.topic = properties.topics().accountEvents();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(AccountEvent event) {
        var message = AccountEventMapper.toMessage(event, UUID.randomUUID().toString(), clock.instant());
        jdbc.sql("""
                insert into outbox_event (aggregate_id, topic, event_type, payload)
                values (:aggregateId, :topic, :type, :payload)""")
                .param("aggregateId", event.accountId())
                .param("topic", topic)
                .param("type", message.getPayloadCase().name())
                .param("payload", message.toByteArray())
                .update();
    }
}
