package com.example.savings.adapter.messaging;

import com.example.savings.application.UnknownAccountException;
import com.example.savings.application.UnknownWithdrawalException;
import com.example.savings.config.SavingsProperties;
import com.example.savings.domain.IllegalHoldTransitionException;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaConfig {

    static final String DLT_SUFFIX = ".DLT";

    /**
     * Transient failures (DB hiccup, optimistic-lock conflict with a batch job) are retried a few times.
     * Messages that can never succeed - unparseable, invalid, or violating the withdrawal contract -
     * go straight to the dead-letter topic so they don't block the partition.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> template, MeterRegistry registry,
            SavingsProperties properties) {
        var deadLetters = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        // Registered up front so the series exists at 0: Prometheus' increase() can't see the first
        // increment of a counter that only appears when it happens.
        var deadLettered = registry.counter("savings.messages.dead.lettered",
                "topic", properties.topics().paymentInstructions());
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            deadLetters.accept(record, ex);
            deadLettered.increment();
        };
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 3));
        handler.addNotRetryableExceptions(
                InvalidProtocolBufferException.class,
                IllegalArgumentException.class,
                IllegalHoldTransitionException.class,
                UnknownAccountException.class,
                UnknownWithdrawalException.class);
        return handler;
    }

    // Created if missing, for local development and tests. In production topics are provisioned
    // by infrastructure code, and the instructions topic belongs to the Payments team.

    @Bean
    NewTopic paymentInstructionsTopic(SavingsProperties properties) {
        return topic(properties.topics().paymentInstructions(), properties);
    }

    @Bean
    NewTopic paymentInstructionsDeadLetterTopic(SavingsProperties properties) {
        return topic(properties.topics().paymentInstructions() + DLT_SUFFIX, properties);
    }

    @Bean
    NewTopic accountEventsTopic(SavingsProperties properties) {
        return topic(properties.topics().accountEvents(), properties);
    }

    private static NewTopic topic(String name, SavingsProperties properties) {
        return TopicBuilder.name(name).partitions(properties.topics().partitions()).replicas(1).build();
    }
}
