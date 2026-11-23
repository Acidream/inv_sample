package com.example.savings.adapter.messaging;

import com.example.savings.application.UnknownAccountException;
import com.example.savings.application.UnknownTransferException;
import com.example.savings.config.SavingsProperties;
import com.example.savings.domain.IllegalTransferException;
import com.example.savings.domain.order.IllegalOrderTransitionException;
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
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
class KafkaConfig {

    static final String DLT_SUFFIX = ".DLT";
    static final long INITIAL_RETRY_INTERVAL_MS = 500;
    static final long MAX_RETRY_INTERVAL_MS = 30_000;

    /**
     * Messages that can never succeed - unparseable, invalid, or contradicting an earlier transfer -
     * go straight to the dead-letter topic so they don't block the partition.
     *
     * <p>Everything else is treated as transient (database failover, optimistic-lock conflict with a
     * batch job) and retried <em>without limit</em>, backing off up to {@link #MAX_RETRY_INTERVAL_MS}.
     * A dead letter needs a person; an outage of a few seconds or minutes must not park every
     * instruction in flight. A message that keeps failing blocks its partition, which the consumer-lag
     * alert reports.
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
        var backOff = new ExponentialBackOff(INITIAL_RETRY_INTERVAL_MS, 2.0);
        backOff.setMaxInterval(MAX_RETRY_INTERVAL_MS);
        // Default max elapsed time and attempts are unlimited: transient errors never dead-letter.
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                InvalidProtocolBufferException.class,
                IllegalArgumentException.class,
                IllegalTransferException.class,
                UnknownAccountException.class,
                UnknownTransferException.class,
                IllegalOrderTransitionException.class);
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

    @Bean
    NewTopic postingsTopic(SavingsProperties properties) {
        return topic(properties.topics().postings(), properties);
    }

    @Bean
    NewTopic savingsCommandsTopic(SavingsProperties properties) {
        return topic(properties.topics().savingsCommands(), properties);
    }

    @Bean
    NewTopic savingsCommandsDeadLetterTopic(SavingsProperties properties) {
        return topic(properties.topics().savingsCommands() + DLT_SUFFIX, properties);
    }

    // The transfer topics belong to the Payments team; created here only for local development and tests.

    @Bean
    NewTopic transferRequestsTopic(SavingsProperties properties) {
        return topic(properties.topics().transferRequests(), properties);
    }

    @Bean
    NewTopic transferEventsTopic(SavingsProperties properties) {
        return topic(properties.topics().transferEvents(), properties);
    }

    @Bean
    NewTopic transferEventsDeadLetterTopic(SavingsProperties properties) {
        return topic(properties.topics().transferEvents() + DLT_SUFFIX, properties);
    }

    private static NewTopic topic(String name, SavingsProperties properties) {
        return TopicBuilder.name(name).partitions(properties.topics().partitions()).replicas(1).build();
    }
}
