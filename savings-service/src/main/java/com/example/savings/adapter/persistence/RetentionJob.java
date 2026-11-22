package com.example.savings.adapter.persistence;

import com.example.savings.config.SavingsProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly clean-up of the two technical tables that otherwise grow forever (ADR 0006, 0007):
 * <ul>
 *   <li>{@code outbox_event}: rows already published to Kafka, kept a while for diagnosis.</li>
 *   <li>{@code processed_event}: ids of consumed messages. The window must be longer than any
 *       redelivery could take; a transfer re-sent after that is still caught by the {@code transfer}
 *       table, which is business data and kept.</li>
 * </ul>
 * Deletes in small batches, one short transaction each, so it never holds long locks or bloats WAL.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private static final int BATCH_SIZE = 5_000;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SavingsProperties.Retention retention;

    RetentionJob(JdbcClient jdbc, Clock clock, SavingsProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = properties.retention();
    }

    @Scheduled(cron = "${savings.jobs.retention-cron}", zone = "UTC")
    @SchedulerLock(name = "retention", lockAtMostFor = "PT1H")
    public void run() {
        var outbox = deleteInBatches("""
                delete from outbox_event where id in (
                    select id from outbox_event where published_at < :cutoff order by id limit :limit)""",
                cutoff(retention.publishedOutboxEvents()));
        var processed = deleteInBatches("""
                delete from processed_event where event_id in (
                    select event_id from processed_event where processed_at < :cutoff limit :limit)""",
                cutoff(retention.processedEvents()));
        log.info("Retention: deleted {} published outbox events and {} processed message ids", outbox, processed);
    }

    private Instant cutoff(Duration keep) {
        return clock.instant().minus(keep);
    }

    private long deleteInBatches(String sql, Instant cutoff) {
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.sql(sql).param("cutoff", cutoff).param("limit", BATCH_SIZE).update();
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
