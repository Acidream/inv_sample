package com.example.platform.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Nightly clean-up of the two technical tables that otherwise grow forever (ADR 0006, 0007):
 * <ul>
 *   <li>{@code outbox_event}: rows already published to Kafka, kept a while for diagnosis.</li>
 *   <li>{@code processed_message}: ids of consumed messages. The window must be longer than any
 *       redelivery could take; the same thing sent again later is caught by its business key
 *       (transfer id, order id, payment id), which is business data and kept.</li>
 * </ul>
 * Deletes in small batches, one short transaction each, so it never holds long locks or bloats WAL.
 */
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    static final int BATCH_SIZE = 5_000;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration keepPublishedOutboxMessages;
    private final Duration keepProcessedMessages;

    public RetentionJob(JdbcClient jdbc, Clock clock, Duration keepPublishedOutboxMessages,
            Duration keepProcessedMessages) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.keepPublishedOutboxMessages = keepPublishedOutboxMessages;
        this.keepProcessedMessages = keepProcessedMessages;
    }

    @Scheduled(cron = "${platform.messaging.retention.cron:0 30 3 * * *}", zone = "UTC")
    @SchedulerLock(name = "retention", lockAtMostFor = "PT1H")
    public void run() {
        var outbox = deleteInBatches("""
                delete from outbox_event where id in (
                    select id from outbox_event where published_at < :cutoff order by id limit :limit)""",
                cutoff(keepPublishedOutboxMessages));
        var processed = deleteInBatches("""
                delete from processed_message where message_id in (
                    select message_id from processed_message where processed_at < :cutoff limit :limit)""",
                cutoff(keepProcessedMessages));
        log.info("Retention: deleted {} published outbox messages and {} processed message ids", outbox, processed);
    }

    private Instant cutoff(Duration keep) {
        return clock.instant().minus(keep);
    }

    private long deleteInBatches(String sql, Instant cutoff) {
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.sql(sql).param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).param("limit", BATCH_SIZE)
                    .update();
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
