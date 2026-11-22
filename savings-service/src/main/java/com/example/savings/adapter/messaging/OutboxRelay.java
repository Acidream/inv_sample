package com.example.savings.adapter.messaging;

import com.example.savings.config.SavingsProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
 * account id, so events of one account keep their order on one partition. ShedLock keeps a single
 * relay active across instances, which is what preserves that order. Rows are marked published only
 * after Kafka acknowledged them; a crash in between re-sends them (at-least-once).
 */
@Component
class OutboxRelay {

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
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M")
    public void relay() {
        while (relayBatch() == batchSize) {
            // keep draining while there is a backlog
        }
    }

    int relayBatch() {
        Integer sent = tx.execute(status -> {
            var rows = jdbc.sql("""
                    select id, topic, aggregate_id, payload from outbox_event
                    where published_at is null
                    order by id
                    limit :limit
                    for update skip locked""")
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
            CompletableFuture.allOf(acks).orTimeout(30, TimeUnit.SECONDS).join();

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
