package com.example.platform.messaging;

import java.time.Duration;
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
 *
 * <p>Ages are measured on the database clock: {@code published_at} and {@code processed_at} are
 * written with the database's {@code now()}, so the cutoff is too. The service's business
 * {@code Clock}, which tests move around, is deliberately not used.
 */
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    static final int BATCH_SIZE = 5_000;

    private final JdbcClient jdbc;
    private final Duration keepPublishedOutboxMessages;
    private final Duration keepProcessedMessages;

    public RetentionJob(JdbcClient jdbc, Duration keepPublishedOutboxMessages, Duration keepProcessedMessages) {
        this.jdbc = jdbc;
        this.keepPublishedOutboxMessages = keepPublishedOutboxMessages;
        this.keepProcessedMessages = keepProcessedMessages;
    }

    @Scheduled(cron = "${platform.messaging.retention.cron:0 30 3 * * *}", zone = "UTC")
    @SchedulerLock(name = "retention", lockAtMostFor = "PT1H")
    public void run() {
        var outbox = deleteInBatches("""
                delete from outbox_event where id in (
                    select id from outbox_event
                    where published_at < now() - :keepSeconds * interval '1 second'
                    order by id limit :limit)""",
                keepPublishedOutboxMessages);
        var processed = deleteInBatches("""
                delete from processed_message where message_id in (
                    select message_id from processed_message
                    where processed_at < now() - :keepSeconds * interval '1 second'
                    limit :limit)""",
                keepProcessedMessages);
        log.info("Retention: deleted {} published outbox messages and {} processed message ids", outbox, processed);
    }

    private long deleteInBatches(String sql, Duration keep) {
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.sql(sql).param("keepSeconds", keep.toSeconds()).param("limit", BATCH_SIZE).update();
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
