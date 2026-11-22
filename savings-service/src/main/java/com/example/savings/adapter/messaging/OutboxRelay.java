package com.example.savings.adapter.messaging;

import com.example.savings.config.SavingsProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox, read side. Polls unpublished rows in insertion order and sends them keyed by
 * account id, so events of one account keep their order on one partition. Rows are marked published
 * only after Kafka acknowledged them; a crash in between re-sends them (at-least-once).
 *
 * <p>Order across instances rests on two guards:
 * <ul>
 *   <li>ShedLock keeps a single relay active. One drain is time-boxed to {@link #DRAIN_BUDGET}, and a
 *       batch waits at most {@link #ACK_TIMEOUT} for Kafka, so a drain always ends well within
 *       {@code lockAtMostFor} and the lock can't expire under a running relay.</li>
 *   <li>Rows are locked with plain {@code for update}, not {@code skip locked}: should a second relay
 *       ever run anyway, it waits for the first one's batch instead of skipping ahead and sending
 *       newer events of the same account first.</li>
 * </ul>
 */
@Component
class OutboxRelay {

    static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DRAIN_BUDGET = Duration.ofSeconds(60);

    private record Row(long id, String topic, UUID key, byte[] payload) {
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Counter published;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, byte[]> kafka, TransactionTemplate tx,
            SavingsProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = properties.outbox().batchSize();
        this.published = Counter.builder("savings.outbox.published").register(registry);
        Gauge.builder("savings.outbox.pending", this::pendingCount)
                .description("Events written but not yet sent to Kafka")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${savings.outbox.poll-interval-ms}")
    // lockAtMostFor must stay above DRAIN_BUDGET + ACK_TIMEOUT (plus DB time): see the class comment.
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT5M")
    public void relay() {
        // Elapsed wall time, not the business Clock (which tests move around).
        long deadline = System.nanoTime() + DRAIN_BUDGET.toNanos();
        while (relayBatch() == batchSize && System.nanoTime() - deadline < 0) {
            // keep draining while there is a backlog; the next poll continues after the budget
        }
    }

    int relayBatch() {
        Integer sent = tx.execute(status -> {
            var rows = jdbc.sql("""
                    select id, topic, aggregate_id, payload from outbox_event
                    where published_at is null
                    order by id
                    limit :limit
                    for update""")
                    .param("limit", batchSize)
                    .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("topic"),
                            rs.getObject("aggregate_id", UUID.class), rs.getBytes("payload")))
                    .list();
            if (rows.isEmpty()) {
                return 0;
            }
            var acks = rows.stream()
                    .map(row -> kafka.send(row.topic(), row.key().toString(), row.payload()))
                    .toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(acks).orTimeout(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();

            List<Long> ids = rows.stream().map(Row::id).toList();
            jdbc.sql("update outbox_event set published_at = now() where id in (:ids)").param("ids", ids).update();
            published.increment(rows.size());
            return rows.size();
        });
        return sent == null ? 0 : sent;
    }

    private long pendingCount() {
        return jdbc.sql("select count(*) from outbox_event where published_at is null").query(Long.class).single();
    }
}
