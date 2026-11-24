package com.example.fixgateway.support;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;

/**
 * Plays investments: produces orders and records every report the gateway publishes, including dead
 * letters. Plain Kafka clients, independent of the
 * application's config.
 */
public class KafkaTestSupport implements DisposableBean {

    public static final String ORDERS = "broker.orders.v1";
    public static final String EXECUTIONS = "broker.executions.v1";
    private static final List<String> OBSERVED = List.of(EXECUTIONS, ORDERS + ".DLT");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final KafkaProducer<String, byte[]> producer;
    private final List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
    private final Thread consumerThread;
    private volatile boolean running = true;

    KafkaTestSupport(String bootstrapServers) {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        var consumer = new KafkaConsumer<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-observer-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                // The application creates the topics on startup; don't let the broker auto-create them first.
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
        consumerThread = Thread.ofPlatform().daemon().name("kafka-test-observer").start(() -> observe(consumer));
    }

    public void send(String topic, String key, byte[] value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test message", e);
        }
    }

    /** Waits for, and consumes, the first not yet consumed message on the topic that matches. */
    public <T extends Message> T await(String topic, Parser<T> parser, Predicate<T> matches, String what) {
        return await(topic, parser, matches, what, TIMEOUT);
    }

    /** As {@link #await(String, Parser, Predicate, String)}, giving up after {@code timeout}. */
    public <T extends Message> T await(String topic, Parser<T> parser, Predicate<T> matches, String what,
            Duration timeout) {
        return poll(() -> {
            synchronized (received) {
                for (var record : received) {
                    if (record.topic().equals(topic)) {
                        var message = parse(parser, record.value());
                        if (matches.test(message)) {
                            received.remove(record);
                            return Optional.of(message);
                        }
                    }
                }
                return Optional.<T>empty();
            }
        }, what, timeout);
    }

    /** Waits until a message with this key was dead-lettered from the topic. */
    public ConsumerRecord<String, byte[]> awaitDeadLetter(String topic, String key) {
        return poll(() -> {
            synchronized (received) {
                return received.stream()
                        .filter(r -> r.topic().equals(topic + ".DLT") && key.equals(r.key()))
                        .findFirst();
            }
        }, "dead letter on " + topic + " with key " + key, TIMEOUT);
    }

    private static <T extends Message> T parse(Parser<T> parser, byte[] bytes) {
        try {
            return parser.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T poll(Supplier<Optional<T>> probe, String what, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var found = probe.get();
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private void observe(KafkaConsumer<String, byte[]> consumer) {
        try (consumer) {
            consumer.subscribe(OBSERVED);
            while (running) {
                for (var record : consumer.poll(Duration.ofMillis(100))) {
                    synchronized (received) {
                        received.add(record);
                    }
                }
            }
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        running = false;
        consumerThread.join(2000);
        producer.close();
    }
}
