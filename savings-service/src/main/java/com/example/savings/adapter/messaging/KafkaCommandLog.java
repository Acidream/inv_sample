package com.example.savings.adapter.messaging;

import com.example.savings.application.port.CommandLog;
import com.example.savings.config.SavingsProperties;
import com.example.savings.domain.order.Order;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends commands straight to Kafka, not through the outbox: accepting a command changes no state,
 * so there is no database transaction to stay consistent with. Returns once Kafka has acknowledged
 * the command ({@code acks=all}).
 */
@Component
class KafkaCommandLog implements CommandLog {

    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;

    KafkaCommandLog(KafkaTemplate<String, byte[]> kafka, SavingsProperties properties) {
        this.kafka = kafka;
        this.topic = properties.topics().savingsCommands();
    }

    @Override
    public void openAndFund(String commandId, Order.Request request) {
        var message = SavingsCommandTranslator.toMessage(commandId, request);
        try {
            kafka.send(topic, request.orderId().toString(), message.toByteArray())
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandLogUnavailableException("Interrupted while appending order " + request.orderId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new CommandLogUnavailableException("Could not append order " + request.orderId(), e);
        }
    }
}
