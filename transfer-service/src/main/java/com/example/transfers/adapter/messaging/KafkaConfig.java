package com.example.transfers.adapter.messaging;

import com.example.transfers.config.TransferProperties;
import com.example.transfers.domain.IllegalTransferStateException;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Error handling as in savings (ADR 0007): messages that can never succeed (unparseable, invalid,
 * contradicting a transfer's state) go to {@code <topic>.DLT}; everything else is treated as transient
 * and retried without limit, backing off up to 30 s.
 */
@Configuration
class KafkaConfig {

    static final String DLT_SUFFIX = ".DLT";

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> template, MeterRegistry registry) {
        var deadLetters = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        var deadLettered = registry.counter("transfers.messages.dead.lettered");
        var backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(30_000);
        var handler = new DefaultErrorHandler((record, ex) -> {
            deadLetters.accept(record, ex);
            deadLettered.increment();
        }, backOff);
        handler.addNotRetryableExceptions(InvalidProtocolBufferException.class, IllegalArgumentException.class,
                IllegalTransferStateException.class);
        return handler;
    }

    /**
     * Created if missing, for local development and tests; in production topics are provisioned by
     * infrastructure code, and the savings and current account topics belong to those teams.
     */
    @Bean
    KafkaAdmin.NewTopics topics(TransferProperties properties) {
        var topics = properties.topics();
        return new KafkaAdmin.NewTopics(
                topic(topics.transferRequests(), topics.partitions()),
                topic(topics.transferRequests() + DLT_SUFFIX, topics.partitions()),
                topic(topics.transferEvents(), topics.partitions()),
                topic(topics.savingsInstructions(), topics.partitions()),
                topic(topics.currentAccountInstructions(), topics.partitions()),
                topic(topics.savingsEvents(), topics.partitions()),
                topic(topics.savingsEvents() + DLT_SUFFIX, topics.partitions()),
                topic(topics.currentAccountEvents(), topics.partitions()),
                topic(topics.currentAccountEvents() + DLT_SUFFIX, topics.partitions()));
    }

    private static NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}
