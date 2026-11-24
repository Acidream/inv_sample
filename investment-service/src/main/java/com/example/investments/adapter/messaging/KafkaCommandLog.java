package com.example.investments.adapter.messaging;

import com.example.investments.application.Command;
import com.example.investments.application.port.CommandLog;
import com.example.investments.config.InvestmentProperties;
import com.example.platform.messaging.SynchronousSender;
import com.example.platform.messaging.SynchronousSender.SendFailedException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Appends commands from the REST intake straight to Kafka (ADR 0019). Keyed by portfolio id, so the
 * commands for one portfolio are applied in the order they were accepted.
 */
@Component
class KafkaCommandLog implements CommandLog {

    private final SynchronousSender sender;
    private final String topic;

    KafkaCommandLog(SynchronousSender sender, InvestmentProperties properties) {
        this.sender = sender;
        this.topic = properties.topics().commands();
    }

    @Override
    public void append(Command command) {
        var message = WireFormat.toMessage(UUID.randomUUID().toString(), command);
        try {
            sender.send(topic, command.portfolioId().toString(), message.toByteArray());
        } catch (SendFailedException e) {
            throw new CommandLogUnavailableException(e.getMessage(), e);
        }
    }
}
