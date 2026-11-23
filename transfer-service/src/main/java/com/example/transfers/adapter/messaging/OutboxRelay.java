package com.example.transfers.adapter.messaging;

import com.example.transfers.config.TransferProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox, read side; the same design as savings' relay (ADR 0006): one relay at a time
 * (ShedLock), rows in insertion order locked with plain {@code for update}, marked published only
 * after Kafka acknowledged them.
 */
@Component
class OutboxRelay {

    static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DRAIN_BUDGET = Duration.ofSeconds(60);

    private record Row(long id, String topic, String key, byte[] payload) {
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, byte[]> kafka, TransactionTemplate tx,
            TransferProperties properties) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = properties.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${transfers.outbox.poll-interval-ms}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT5M")
    public void relay() {
        long deadline = System.nanoTime() + DRAIN_BUDGET.toNanos();
        while (relayBatch() == batchSize && System.nanoTime() - deadline < 0) {
            // keep draining while there is a backlog; the next poll continues after the budget
        }
    }

    int relayBatch() {
        Integer sent = tx.execute(status -> {
            var rows = jdbc.sql("""
                    select id, topic, message_key, payload from outbox_event
                    where published_at is null
                    order by id
                    limit :limit
                    for update""")
                    .param("limit", batchSize)
                    .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("topic"), rs.getString("message_key"),
                            rs.getBytes("payload")))
                    .list();
            if (rows.isEmpty()) {
                return 0;
            }
            var acks = rows.stream()
                    .map(row -> kafka.send(row.topic(), row.key(), row.payload()))
                    .toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(acks).orTimeout(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
            List<Long> ids = rows.stream().map(Row::id).toList();
            jdbc.sql("update outbox_event set published_at = now() where id in (:ids)").param("ids", ids).update();
            return rows.size();
        });
        return sent == null ? 0 : sent;
    }
}
