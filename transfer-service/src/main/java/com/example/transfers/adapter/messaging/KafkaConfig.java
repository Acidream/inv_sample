package com.example.transfers.adapter.messaging;

import com.example.platform.messaging.DeadLetters;
import com.example.transfers.application.AccountConflictException;
import com.example.transfers.config.TransferProperties;
import com.example.transfers.domain.IllegalTransferStateException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Error handling (ADR 0007): unparseable or invalid messages, answers contradicting a transfer's
 * state, and an account id claimed twice with different details are dead-lettered; everything else
 * is retried.
 */
@Configuration
class KafkaConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> template, MeterRegistry registry,
            TransferProperties properties) {
        var topics = properties.topics();
        return DeadLetters.errorHandler(template, registry,
                List.of(topics.transferRequests(), topics.savingsEvents(), topics.currentAccountEvents()),
                List.of(IllegalTransferStateException.class, AccountConflictException.class));
    }

    /**
     * For local development and tests; the savings and current account topics belong to those
     * teams.
     */
    @Bean
    KafkaAdmin.NewTopics topics(TransferProperties properties) {
        var topics = properties.topics();
        return DeadLetters.topics(topics.partitions(), List.of(
                topics.transferRequests(), DeadLetters.of(topics.transferRequests()),
                topics.transferEvents(),
                topics.savingsInstructions(),
                topics.currentAccountInstructions(),
                topics.savingsEvents(), DeadLetters.of(topics.savingsEvents()),
                topics.currentAccountEvents(), DeadLetters.of(topics.currentAccountEvents())));
    }
}
