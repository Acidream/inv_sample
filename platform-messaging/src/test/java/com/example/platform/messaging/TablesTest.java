package com.example.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The SQL of the outbox, the idempotent consumer and the retention job against real Postgres, on
 * the schema {@code package-info} documents. The relay is covered by the services' acceptance tests,
 * which run it against Kafka.
 */
@Testcontainers
class TablesTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final Instant NOW = Instant.parse("2026-11-23T03:30:00Z");
    private static JdbcClient jdbc;

    @BeforeAll
    static void schema() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                create table outbox_event (
                    id bigserial primary key, message_key varchar(100) not null, topic varchar(200) not null,
                    event_type varchar(100) not null, payload bytea not null,
                    created_at timestamptz not null default now(), published_at timestamptz)""").update();
        jdbc.sql("""
                create table processed_message (
                    message_id varchar(200) primary key, processed_at timestamptz not null default now())""")
                .update();
    }

    @BeforeEach
    void empty() {
        jdbc.sql("truncate outbox_event, processed_message").update();
    }

    @Test
    void aMessageIdIsProcessedOnce() {
        var processed = new ProcessedMessages(jdbc);
        assertThat(processed.markProcessed("command:1")).isTrue();
        assertThat(processed.markProcessed("command:1")).isFalse();
        assertThat(processed.markProcessed("instruction:1")).isTrue();
    }

    @Test
    void theOutboxStoresTheMessageUnpublished() {
        new Outbox(jdbc).add("topic.v1", "key-1", "OPENED", new byte[] {1, 2, 3});
        var row = jdbc.sql("select message_key, topic, event_type, payload, published_at from outbox_event")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getBytes(4),
                        rs.getObject(5)})
                .single();
        assertThat(row).containsExactly("key-1", "topic.v1", "OPENED", new byte[] {1, 2, 3}, null);
    }

    @Test
    void retentionDeletesOnlyWhatIsOldEnough() {
        insertOutbox(NOW.minus(Duration.ofDays(8)));          // published long ago: deleted
        insertOutbox(NOW.minus(Duration.ofDays(6)));          // published recently: kept
        insertOutbox(null);                                   // never published: kept, however old
        insertProcessed("old", NOW.minus(Duration.ofDays(31)));
        insertProcessed("recent", NOW.minus(Duration.ofDays(29)));

        new RetentionJob(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7), Duration.ofDays(30)).run();

        assertThat(jdbc.sql("select count(*) from outbox_event").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from outbox_event where published_at is null").query(Long.class)
                .single()).isEqualTo(1);
        assertThat(jdbc.sql("select message_id from processed_message").query(String.class).list())
                .containsExactly("recent");
    }

    @Test
    void retentionDeletesInBatchesUntilNothingIsLeft() {
        jdbc.sql("""
                insert into processed_message (message_id, processed_at)
                select 'id-' || n, :at from generate_series(1, :count) n""")
                .param("at", NOW.minus(Duration.ofDays(40)).atOffset(ZoneOffset.UTC))
                .param("count", RetentionJob.BATCH_SIZE * 2 + 1)
                .update();

        new RetentionJob(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7), Duration.ofDays(30)).run();

        assertThat(jdbc.sql("select count(*) from processed_message").query(Long.class).single()).isZero();
    }

    private static void insertOutbox(Instant publishedAt) {
        jdbc.sql("""
                insert into outbox_event (message_key, topic, event_type, payload, published_at)
                values ('k', 't', 'T', '\\x00', :at)""")
                .param("at", publishedAt == null ? null : publishedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static void insertProcessed(String id, Instant at) {
        jdbc.sql("insert into processed_message (message_id, processed_at) values (:id, :at)")
                .param("id", id)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }
}
