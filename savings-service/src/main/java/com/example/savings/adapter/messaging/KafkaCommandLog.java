package com.example.savings.adapter.messaging;

import com.example.platform.messaging.SynchronousSender;
import com.example.platform.messaging.SynchronousSender.SendFailedException;
import com.example.savings.application.port.CommandLog;
import com.example.savings.config.SavingsProperties;
import com.example.savings.domain.order.Order;
import org.springframework.stereotype.Component;

/**
 * Appends commands straight to Kafka, not through the outbox: accepting a command changes no state,
 * so there is no database transaction to stay consistent with. Returns once Kafka has acknowledged
 * the command ({@code acks=all}).
 */
@Component
class KafkaCommandLog implements CommandLog {

    private final SynchronousSender sender;
    private final String topic;

    KafkaCommandLog(SynchronousSender sender, SavingsProperties properties) {
        this.sender = sender;
        this.topic = properties.topics().savingsCommands();
    }

    @Override
    public void openAndFund(String commandId, Order.Request request) {
        var message = SavingsCommandTranslator.toMessage(commandId, request);
        try {
            sender.send(topic, request.orderId().toString(), message.toByteArray());
        } catch (SendFailedException e) {
            throw new CommandLogUnavailableException(e.getMessage(), e);
        }
    }
}
