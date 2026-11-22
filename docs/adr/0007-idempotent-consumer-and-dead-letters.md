# 0007 – Idempotent consumer, retries and a dead-letter topic

Date: 2026-11-22 · Status: Accepted

## Context

Kafka delivers at-least-once: rebalances and crashes redeliver messages. Applying a deposit twice
is a real money bug. Some messages can never succeed (malformed, contradicting earlier instructions)
and must not block the partition behind them.

## Decision

**Idempotency**
- Each instruction is applied in one DB transaction that starts with
  `insert into processed_event (event_id) … on conflict do nothing`. Zero rows inserted means the
  message was already applied: skip it and count `savings_messages_duplicate_total`.
- The marker commits or rolls back together with the business change, so there is no window where
  one exists without the other.
- Offsets are committed per record (`ack-mode: record`) **after** the transaction commits. A crash in
  between causes a redelivery, which the marker turns into a no-op.
- Business-level idempotency on top: a second `WithdrawalRequested` with a known `request_id` is
  ignored; repeating a hold transition that already happened is a no-op (ADR 0009).

**Failures** (`KafkaConfig`)
- Transient errors (DB unavailable, optimistic-lock conflict with a batch job) are retried in place:
  3 times, 500 ms apart.
- Non-retryable errors go straight to `<topic>.DLT` with the original bytes and exception headers:
  unparseable Protobuf, validation errors, unknown account or withdrawal, contract violations such
  as confirming a cancelled withdrawal.
- A rejected withdrawal (insufficient funds) is **not** an error; it's a normal outcome answered with
  a `WithdrawalRejected` event.

## Consequences

- `processed_event` grows with traffic. In production it would get a retention window longer than
  any possible redelivery (e.g. 30 days, partitioned by day and dropped).
- Dead letters need an owner and a runbook (inspect, fix, replay); an alert on DLT traffic belongs in
  the next iteration.
- Verified by the acceptance test "a redelivered message is applied only once", the contract-violation
  scenario and `DeadLetterIT`.
