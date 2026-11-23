package com.example.currentaccount.adapter.messaging;

import com.example.currentaccount.application.Command;
import com.example.currentaccount.application.port.CommandLog;
import com.example.currentaccount.config.CurrentAccountProperties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends commands from the REST intake straight to Kafka: accepting a command changes no state, so
 * there is no transaction to stay consistent with (ADR 0019). Keyed by account id, so the commands
 * for one account are applied in the order they were accepted.
 */
@Component
class KafkaCommandLog implements CommandLog {

    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;

    KafkaCommandLog(KafkaTemplate<String, byte[]> kafka, CurrentAccountProperties properties) {
        this.kafka = kafka;
        this.topic = properties.topics().commands();
    }

    @Override
    public void append(Command command) {
        var message = WireFormat.toMessage(UUID.randomUUID().toString(), command);
        var key = command.accountId().toString();
        try {
            kafka.send(topic, key, message.toByteArray()).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandLogUnavailableException("Interrupted while appending a command for " + key, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new CommandLogUnavailableException("Could not append a command for account " + key, e);
        }
    }
}
