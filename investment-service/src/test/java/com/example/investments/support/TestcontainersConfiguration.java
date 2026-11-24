package com.example.investments.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real Postgres and Kafka in Docker; Spring Boot wires the connection details automatically. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.0.0");
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    KafkaTestSupport kafkaTestSupport(KafkaContainer kafka) {
        return new KafkaTestSupport(kafka.getBootstrapServers());
    }
}
