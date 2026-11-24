package com.example.fixgateway.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real Postgres and Kafka in Docker, wired by Spring Boot; the broker is a FIX acceptor in the test
 * JVM ({@link TestBroker}).
 */
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
    TestBroker testBroker() throws Exception {
        return new TestBroker();
    }

    /** The gateway connects to the test broker, wherever it listens. */
    @Bean
    DynamicPropertyRegistrar brokerPort(TestBroker broker) {
        return registry -> registry.add("gateway.fix.port", broker::port);
    }

    @Bean
    KafkaTestSupport kafkaTestSupport(KafkaContainer kafka) {
        return new KafkaTestSupport(kafka.getBootstrapServers());
    }
}
