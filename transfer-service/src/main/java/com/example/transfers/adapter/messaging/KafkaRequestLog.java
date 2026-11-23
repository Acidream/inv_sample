package com.example.transfers.adapter.messaging;

import com.example.platform.messaging.SynchronousSender;
import com.example.platform.messaging.SynchronousSender.SendFailedException;
import com.example.transfers.application.port.RequestLog;
import com.example.transfers.config.TransferProperties;
import com.example.transfers.domain.Transfer;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Appends requests from the REST intake straight to Kafka: accepting a request changes no state, so
 * there is no transaction to stay consistent with (ADR 0019).
 */
@Component
class KafkaRequestLog implements RequestLog {

    private final SynchronousSender sender;
    private final String topic;

    KafkaRequestLog(SynchronousSender sender, TransferProperties properties) {
        this.sender = sender;
        this.topic = properties.topics().transferRequests();
    }

    @Override
    public void append(Transfer.Request request) {
        var message = WireFormat.toMessage(UUID.randomUUID().toString(), request);
        try {
            sender.send(topic, request.transferId(), message.toByteArray());
        } catch (SendFailedException e) {
            throw new RequestLogUnavailableException(e.getMessage(), e);
        }
    }
}
