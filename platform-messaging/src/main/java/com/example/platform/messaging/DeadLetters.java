package com.example.platform.messaging;

import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Error handling for consumers (ADR 0007), and the topics that go with it.
 *
 * <p>Messages that can never succeed (unparseable, invalid, or contradicting what was applied
 * before) go straight to {@code <topic>.DLT}, so they don't block the partition. Everything else is
 * treated as transient (database failover, a lock conflict) and retried <em>without limit</em>,
 * backing off up to {@link #MAX_RETRY_INTERVAL_MS}: a dead letter needs a person, and an outage of
 * a few minutes must not park every message in flight. A message that keeps failing blocks its
 * partition, which the consumer-lag alert reports.
 *
 * <p>Metric: {@code messages.dead.lettered}, tagged with the topic the message came from.
 */
public final class DeadLetters {

    public static final String SUFFIX = ".DLT";
    static final long INITIAL_RETRY_INTERVAL_MS = 500;
    static final long MAX_RETRY_INTERVAL_MS = 30_000;

    private DeadLetters() {
    }

    /** The dead-letter topic of a topic. */
    public static String of(String topic) {
        return topic + SUFFIX;
    }

    /**
     * @param consumedTopics the topics the service consumes: their counters are registered at zero up
     *                       front, because Prometheus' {@code increase()} can't see the first increment
     *                       of a series that only appears when it happens
     * @param notRetryable   the service's own exceptions that mean "never succeeds", on top of
     *                       {@link InvalidProtocolBufferException} and {@link IllegalArgumentException}
     */
    public static CommonErrorHandler errorHandler(KafkaTemplate<String, byte[]> template, MeterRegistry registry,
            Collection<String> consumedTopics, Collection<Class<? extends Exception>> notRetryable) {
        var deadLetters = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(of(record.topic()), -1));
        Map<String, Counter> counters = new ConcurrentHashMap<>();
        consumedTopics.forEach(topic -> counters.put(topic, counter(registry, topic)));
        var backOff = new ExponentialBackOff(INITIAL_RETRY_INTERVAL_MS, 2.0);
        backOff.setMaxInterval(MAX_RETRY_INTERVAL_MS);
        // Default max elapsed time and attempts are unlimited: transient errors never dead-letter.
        var handler = new DefaultErrorHandler((record, ex) -> {
            deadLetters.accept(record, ex);
            counters.computeIfAbsent(record.topic(), topic -> counter(registry, topic)).increment();
        }, backOff);
        List<Class<? extends Exception>> never = new ArrayList<>(
                List.of(InvalidProtocolBufferException.class, IllegalArgumentException.class));
        never.addAll(notRetryable);
        never.forEach(handler::addNotRetryableExceptions);
        return handler;
    }

    /**
     * Topics created if missing, for local development and tests; in production topics are
     * provisioned by infrastructure code.
     */
    public static KafkaAdmin.NewTopics topics(int partitions, Collection<String> names) {
        return new KafkaAdmin.NewTopics(names.stream()
                .map(name -> TopicBuilder.name(name).partitions(partitions).replicas(1).build())
                .toArray(NewTopic[]::new));
    }

    private static Counter counter(MeterRegistry registry, String topic) {
        return Counter.builder("messages.dead.lettered")
                .description("Messages parked on a dead-letter topic")
                .tag("topic", topic)
                .register(registry);
    }
}
