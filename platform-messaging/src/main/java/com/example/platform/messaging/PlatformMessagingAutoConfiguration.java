package com.example.platform.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything a service gets by depending on this library (ADR 0022). The service provides the
 * tables described in {@code package-info} and its own error handler built with
 * {@link DeadLetters#errorHandler}.
 *
 * <p>Scheduling and ShedLock are switched on here: the relay and the retention job, and the
 * service's own jobs, run on one instance at a time; the lock lives in Postgres and uses DB time.
 */
@AutoConfiguration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
@EnableConfigurationProperties(PlatformMessagingProperties.class)
public class PlatformMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Bean
    Outbox outbox(JdbcClient jdbc) {
        return new Outbox(jdbc);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, byte[]> kafka, TransactionTemplate tx,
            PlatformMessagingProperties properties, MeterRegistry registry) {
        return new OutboxRelay(jdbc, kafka, tx, properties.outbox().batchSize(), registry);
    }

    @Bean
    ProcessedMessages processedMessages(JdbcClient jdbc) {
        return new ProcessedMessages(jdbc);
    }

    @Bean
    RetentionJob retentionJob(JdbcClient jdbc, PlatformMessagingProperties properties) {
        var retention = properties.retention();
        return new RetentionJob(jdbc, retention.publishedOutboxMessages(), retention.processedMessages());
    }

    @Bean
    SynchronousSender synchronousSender(KafkaTemplate<String, byte[]> kafka) {
        return new SynchronousSender(kafka);
    }
}
