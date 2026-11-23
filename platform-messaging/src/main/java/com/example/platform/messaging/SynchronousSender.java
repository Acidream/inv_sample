package com.example.platform.messaging;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends straight to Kafka and waits for the acknowledgement ({@code acks=all}). For a command log
 * (ADR 0019): accepting a command changes no state, so there is no database transaction to stay
 * consistent with and no outbox is needed; but the caller must know whether it was accepted.
 */
public class SynchronousSender {

    static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, byte[]> kafka;

    public SynchronousSender(KafkaTemplate<String, byte[]> kafka) {
        this.kafka = kafka;
    }

    /** @throws SendFailedException if Kafka didn't acknowledge in time; the message may or may not be on the topic */
    public void send(String topic, String key, byte[] payload) {
        try {
            kafka.send(topic, key, payload).get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SendFailedException("Interrupted while sending to " + topic + " with key " + key, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new SendFailedException("Could not send to " + topic + " with key " + key, e);
        }
    }

    public static class SendFailedException extends RuntimeException {

        SendFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
