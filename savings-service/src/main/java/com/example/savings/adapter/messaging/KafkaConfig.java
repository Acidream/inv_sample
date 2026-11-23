package com.example.savings.adapter.messaging;

import com.example.platform.messaging.DeadLetters;
import com.example.savings.application.UnknownAccountException;
import com.example.savings.application.UnknownTransferException;
import com.example.savings.config.SavingsProperties;
import com.example.savings.domain.IllegalTransferException;
import com.example.savings.domain.order.IllegalOrderTransitionException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;

@Configuration
class KafkaConfig {

    /**
     * Messages that can never succeed (unparseable, invalid, contradicting an earlier transfer, for
     * an account or transfer savings doesn't have) are dead-lettered; everything else is retried
     * without limit (ADR 0007, {@link DeadLetters}).
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> template, MeterRegistry registry,
            SavingsProperties properties) {
        var topics = properties.topics();
        return DeadLetters.errorHandler(template, registry,
                List.of(topics.paymentInstructions(), topics.savingsCommands(), topics.transferEvents()),
                List.of(IllegalTransferException.class, UnknownAccountException.class,
                        UnknownTransferException.class, IllegalOrderTransitionException.class));
    }

    /**
     * For local development and tests. In production topics are provisioned by infrastructure code;
     * the instructions and transfer topics belong to the Payments team.
     */
    @Bean
    KafkaAdmin.NewTopics topics(SavingsProperties properties) {
        var topics = properties.topics();
        return DeadLetters.topics(topics.partitions(), List.of(
                topics.paymentInstructions(), DeadLetters.of(topics.paymentInstructions()),
                topics.accountEvents(),
                topics.postings(),
                topics.savingsCommands(), DeadLetters.of(topics.savingsCommands()),
                topics.transferRequests(),
                topics.transferEvents(), DeadLetters.of(topics.transferEvents())));
    }
}
