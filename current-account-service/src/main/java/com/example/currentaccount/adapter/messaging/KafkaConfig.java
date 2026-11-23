package com.example.currentaccount.adapter.messaging;

import com.example.currentaccount.application.PaymentIdInUseException;
import com.example.currentaccount.application.TransferIdInUseException;
import com.example.currentaccount.config.CurrentAccountProperties;
import com.example.platform.messaging.DeadLetters;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Error handling (ADR 0007): unparseable or invalid messages, and a payment id or transfer id reused
 * for something else, are dead-lettered; everything else is retried.
 */
@Configuration
class KafkaConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> template, MeterRegistry registry,
            CurrentAccountProperties properties) {
        var topics = properties.topics();
        return DeadLetters.errorHandler(template, registry, List.of(topics.commands(), topics.instructions()),
                List.of(PaymentIdInUseException.class, TransferIdInUseException.class));
    }

    /** For local development and tests; the instructions topic belongs to the Payments team. */
    @Bean
    KafkaAdmin.NewTopics topics(CurrentAccountProperties properties) {
        var topics = properties.topics();
        return DeadLetters.topics(topics.partitions(), List.of(
                topics.commands(), DeadLetters.of(topics.commands()),
                topics.instructions(), DeadLetters.of(topics.instructions()),
                topics.events()));
    }
}
