package com.example.transfers.adapter.messaging;

import com.example.transfers.application.port.RequestLog;
import com.example.transfers.config.TransferProperties;
import com.example.transfers.domain.Transfer;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends requests from the REST intake straight to Kafka: accepting a request changes no state, so
 * there is no transaction to stay consistent with (ADR 0019).
 */
@Component
class KafkaRequestLog implements RequestLog {

    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;

    KafkaRequestLog(KafkaTemplate<String, byte[]> kafka, TransferProperties properties) {
        this.kafka = kafka;
        this.topic = properties.topics().transferRequests();
    }

    @Override
    public void append(Transfer.Request request) {
        var message = WireFormat.toMessage(UUID.randomUUID().toString(), request);
        try {
            kafka.send(topic, request.transferId(), message.toByteArray()).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequestLogUnavailableException("Interrupted while appending " + request.transferId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RequestLogUnavailableException("Could not append transfer " + request.transferId(), e);
        }
    }
}
