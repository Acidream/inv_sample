package com.example.investments.adapter.messaging;

import com.example.investments.application.IllegalInstructionException;
import com.example.investments.application.OrderIdInUseException;
import com.example.investments.application.PortfolioIdInUseException;
import com.example.investments.config.InvestmentProperties;
import com.example.investments.domain.IllegalExecutionException;
import com.example.platform.messaging.DeadLetters;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Error handling (ADR 0007): unparseable or invalid messages, ids reused for something else, and
 * instructions or broker reports that contradict what was applied are dead-lettered; everything else
 * is retried.
 */
@Configuration
class KafkaConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> template, MeterRegistry registry,
            InvestmentProperties properties) {
        var topics = properties.topics();
        return DeadLetters.errorHandler(template, registry,
                List.of(topics.commands(), topics.instructions(), topics.transferEvents(),
                        topics.currentAccountEvents(), topics.brokerExecutions()),
                List.of(PortfolioIdInUseException.class, OrderIdInUseException.class,
                        IllegalInstructionException.class, IllegalExecutionException.class));
    }

    /** For local development and tests; the other topics belong to Payments, Current Account and the gateway. */
    @Bean
    KafkaAdmin.NewTopics topics(InvestmentProperties properties) {
        var topics = properties.topics();
        return DeadLetters.topics(topics.partitions(), List.of(
                topics.commands(), DeadLetters.of(topics.commands()),
                topics.instructions(), DeadLetters.of(topics.instructions()),
                topics.transferEvents(), DeadLetters.of(topics.transferEvents()),
                topics.currentAccountEvents(), DeadLetters.of(topics.currentAccountEvents()),
                topics.brokerExecutions(), DeadLetters.of(topics.brokerExecutions()),
                topics.accountEvents(), topics.transferRequests(), topics.brokerOrders(), topics.postings()));
    }
}
