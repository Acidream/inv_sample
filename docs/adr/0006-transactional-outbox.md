# 0006 – Publish events through a transactional outbox

Date: 2026-11-22 · Status: Accepted

## Context

A business change (e.g. a deposit) must be stored **and** announced. Writing to Postgres and Kafka
separately is a dual write: a crash in between either loses the event (DB committed, Kafka not) or
announces something that never happened (Kafka sent, DB rolled back).

## Decision

- **Write side:** use cases call the `AccountEventPublisher` port; `OutboxAccountEventPublisher`
  serializes the Protobuf event and inserts it into `outbox_event` in the **same transaction**
  (`Propagation.MANDATORY` makes calling it outside a transaction a bug, not a silent loss).
- **Read side:** `OutboxRelay` polls every 200 ms: `select … where published_at is null order by id
  limit 100 for update`, sends all rows, waits for the broker acks, then marks them
  published in the same DB transaction.
- The relay runs on **one instance at a time** (ShedLock). With a single relay reading in `id` order
  and an idempotent producer, events of one account reach Kafka in commit order. Two guards keep it
  that way:
  - One drain is **time-boxed** (60 s, plus at most 30 s for the last batch's acks) and the lock lasts
    5 min, so the lock can't expire while a relay is still sending and let a second relay start.
  - Rows are locked with plain `for update`, **not `skip locked`**: a second relay, should one run
    anyway, waits for the first one's batch instead of skipping ahead and sending newer events of the
    same account first.
- Delivery is **at-least-once**: a crash after the send but before the update re-sends the batch.
  Consumers de-duplicate on `event_id` (as Savings itself does, ADR 0007).

## Alternatives considered

- **CDC with Debezium** reading the WAL: lower latency, no polling, but another piece of
  infrastructure to run. A good upgrade path; the table layout already fits Debezium's outbox router.
- **Kafka transactions** spanning DB and Kafka: not possible, Postgres is not a participant.

## Consequences

- Events are delayed by up to one poll interval.
- The DB transaction is open while Kafka acks a batch (bounded to 100 rows / 30 s timeout).
- If the instance holding the lock dies, relaying stops until the lock expires (up to 5 min); the
  **SavingsOutboxBacklog** alert fires after 5 min of backlog.
- Published rows are deleted after 7 days by `RetentionJob` (`savings.retention.published-outbox-events`).
  A partial index on unpublished rows keeps polling fast regardless.
- Monitoring: `savings_outbox_pending` gauge and the `SavingsOutboxBacklog` alert (ADR 0013).
