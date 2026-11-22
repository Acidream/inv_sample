package com.example.savings.support;

import com.example.events.savings.v1.AccountEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
 * Plays the Payments team: produces instructions and listens to everything Savings publishes,
 * including the dead-letter topic. Plain Kafka clients, independent of the application's config.
 */
public class KafkaTestSupport implements DisposableBean {

    public static final String INSTRUCTIONS = "payments.savings-instructions.v1";
    public static final String DEAD_LETTERS = INSTRUCTIONS + ".DLT";
    public static final String ACCOUNT_EVENTS = "savings.account-events.v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final KafkaProducer<String, byte[]> producer;
    private final List<AccountEvent> events = new ArrayList<>();
    private final List<ConsumerRecord<String, byte[]>> deadLetters = new ArrayList<>();
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
                // The application creates the topics (3 partitions) on startup. Don't let the broker
                // auto-create them first with 1 partition, and pick up new partitions quickly.
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
        consumerThread = Thread.ofPlatform().daemon().name("kafka-test-observer").start(() -> observe(consumer));
    }

    public void send(String key, byte[] value) {
        try {
            producer.send(new ProducerRecord<>(INSTRUCTIONS, key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test message", e);
        }
    }

    /** Waits for, and consumes, the first not yet consumed event of that type for the account. */
    public AccountEvent awaitEvent(String accountId, AccountEvent.PayloadCase type) {
        return await(() -> {
            synchronized (events) {
                var match = events.stream()
                        .filter(e -> e.getAccountId().equals(accountId) && e.getPayloadCase() == type)
                        .findFirst();
                match.ifPresent(events::remove);
                return match;
            }
        }, type + " for account " + accountId);
    }

    public ConsumerRecord<String, byte[]> awaitDeadLetter(String key) {
        return await(() -> {
            synchronized (deadLetters) {
                return deadLetters.stream().filter(r -> key.equals(r.key())).findFirst();
            }
        }, "dead letter with key " + key);
    }

    private <T> T await(java.util.function.Supplier<Optional<T>> probe, String what) {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
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
            consumer.subscribe(List.of(ACCOUNT_EVENTS, DEAD_LETTERS));
            while (running) {
                for (var record : consumer.poll(Duration.ofMillis(100))) {
                    if (record.topic().equals(ACCOUNT_EVENTS)) {
                        synchronized (events) {
                            events.add(parse(record.value()));
                        }
                    } else {
                        synchronized (deadLetters) {
                            deadLetters.add(record);
                        }
                    }
                }
            }
        }
    }

    private static AccountEvent parse(byte[] bytes) {
        try {
            return AccountEvent.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        running = false;
        consumerThread.join(2000);
        producer.close();
    }
}
