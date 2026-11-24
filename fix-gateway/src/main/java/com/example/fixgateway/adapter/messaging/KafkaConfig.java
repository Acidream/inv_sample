package com.example.fixgateway.adapter.messaging;

import com.example.fixgateway.application.OrderIdInUseException;
import com.example.fixgateway.config.GatewayProperties;
import com.example.platform.messaging.DeadLetters;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Error handling (ADR 0007): invalid orders and an order id reused for another order are
 * dead-lettered; everything else is retried.
 */
@Configuration
class KafkaConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> template, MeterRegistry registry,
            GatewayProperties properties) {
        return DeadLetters.errorHandler(template, registry, List.of(properties.topics().orders()),
                List.of(OrderIdInUseException.class));
    }

    /** For local development and tests. */
    @Bean
    KafkaAdmin.NewTopics topics(GatewayProperties properties) {
        var topics = properties.topics();
        return DeadLetters.topics(topics.partitions(), List.of(topics.orders(), DeadLetters.of(topics.orders()),
                topics.executions()));
    }
}
