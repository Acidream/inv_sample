# 0022 – A shared technical library for messaging

Date: 2026-11-23 · Status: Accepted · Amends [0018](0018-service-structure.md), [0021](0021-current-account-service.md)

## Context

ADR 0018 copied the technical building blocks into the transfer service instead of sharing them,
and named the third copy as the point to extract a library. The current account service (ADR 0021)
made that third copy. By then the copies had started to drift:

- only savings cleaned up its outbox and processed-message ids; the two newer services' tables
  grew forever;
- only savings had outbox metrics; the dead-letter counter had three names
  (`savings.messages.dead.lettered`, `transfers.…`, `current_account.…`), and the alert watched
  only savings;
- the same wire checks (id length, NUL characters, amounts that fit `numeric(19, 2)`) existed three
  times, with small differences in how text was shortened;
- the idempotency table was `processed_event(event_id)` in savings and
  `processed_message(message_id)` elsewhere.

## Decision

**One library, `platform-messaging` (package `com.example.platform.messaging`), with the technical
building blocks and nothing else.** A service gets it by depending on it; a Spring Boot
auto-configuration provides the beans.

| Part | What it does | ADR |
|---|---|---|
| `Outbox`, `OutboxRelay` | write side in the business transaction; single ordered relay under ShedLock | 0006 |
| `ProcessedMessages` | idempotent consumer by message id, in the caller's transaction | 0007 |
| `RetentionJob` | nightly batched delete of sent outbox rows and old message ids | 0006, 0007 |
| `DeadLetters` | the error handler (dead-letter what never succeeds, retry the rest without limit) and topic declarations | 0007 |
| `SynchronousSender` | send and wait for `acks=all`, for command logs | 0019 |
| `WireValues` | technical checks of values read from a message | 0005 |
| auto-configuration | scheduling, ShedLock lock provider, properties `platform.messaging.*` | |

**What stays in each service:**

- **`Money` and every other domain type.** ADR 0018 listed `Money` among the copies, but it is a
  domain value with different rules per service: signed with arithmetic and rounding down in
  savings, positive only in the transfer and current account services. And a domain stays plain
  Java (ADR 0016), so it can't depend on a library.
- **Business checks on the wire:** EUR only, positive amounts, which payloads exist. `WireValues`
  only refuses what can't be stored.
- **The ports.** Use cases still depend on their own `ProcessedEvents` / `ProcessedMessages` ports;
  a one-line adapter delegates to the library. The application layer may not import the library
  (ArchUnit).
- **The tables.** Each service owns its database and creates `outbox_event`, `processed_message`
  and `shedlock` with its own Flyway migrations; the library documents the schema it expects
  (`package-info`). Savings renames `processed_event` to `processed_message` (V9).
- **Its error handler bean and topics:** the service passes the topics it consumes and its own
  non-retryable exceptions to `DeadLetters`.

**Rules for the library.** It imports nothing from `com.example` (no service, no contracts),
checked by Checkstyle import control. A change to it is a change to all three services: their
acceptance suites run in the same build. In a real organisation it would be a versioned artifact
owned by a platform team; here it is a module in the same repository.

**One set of metrics for all services**, told apart by their `application` tag:
`outbox_pending`, `outbox_published_total`, `messages_dead_lettered_total{topic}`. The alerts
`MessageDeadLettered` and `OutboxBacklog` replace the savings-only ones and cover every service.

## Consequences

- The transfer and current account services now clean up their technical tables, and their
  outboxes and dead letters are measured and alerted on, as savings' always were.
- The retention SQL is tested against Postgres in the library itself; before, no test ran it.
- About 400 lines of copied code are gone. What a service still writes is its business: listeners,
  translators, publishers.
- Metric and alert names changed (`savings_outbox_pending` → `outbox_pending{application=…}` and so
  on); dashboards and alerts are updated with them.
- Upgrading the relay or the retry policy happens once, for all services. So does a bug in them.
- Test support (Testcontainers config, the Kafka observer, the mutable clock) is still copied per
  service; it differs more between services and changes less. It's a candidate for a test-fixtures
  module if it starts to drift.

## Alternatives considered

- **Keep copying.** It was already drifting after three copies.
- **Share `Money` and other domain types too.** That would couple the domains, which ADR 0018
  keeps apart, and their rules differ.
- **A messaging framework (Axon, Eventuate, Spring Modulith's event publication registry).** It would
  bring its own model of events and storage; this library only packages what the services already
  do, with the guarantees already recorded in ADRs 0006 and 0007.
