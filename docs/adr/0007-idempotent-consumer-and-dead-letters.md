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
- **Business-key idempotency on top**, because the message id only catches redeliveries of the
  *same* message. A producer that re-sends an intent as a new message (e.g. Payments restarting
  without an outbox) would otherwise move money twice:
  - The `transfer` table has the `transfer_id` as primary key (ADR 0015). A transfer already
    applied with the same direction, account and amount is skipped and counted as a duplicate; one
    with different content is a contract violation and goes to the DLT. A rejected withdrawal stays
    rejected when re-sent. (A unique constraint on `ledger_entry.reference` isn't possible:
    partitioned tables need `booked_at` in every unique key.)
  - A return is applied once: the transfer moves `COMPLETED -> RETURNED`; a second return is a no-op.

**Failures** (`KafkaConfig`)
- Transient errors (DB unavailable, optimistic-lock conflict with a batch job) are retried in place:
  3 times, 500 ms apart.
- Non-retryable errors go straight to `<topic>.DLT` with the original bytes and exception headers:
  unparseable Protobuf, validation errors, unknown account or transfer, contract violations such
  as returning a withdrawal that was rejected.
- A rejected withdrawal (insufficient funds) is **not** an error; it's a normal outcome answered with
  a `WithdrawalRejected` event.
- Every dead letter increments `savings_messages_dead_lettered_total{topic}`; the
  **SavingsInstructionDeadLettered** alert pages on any increase. Parked is not lost, but until someone
  acts, the money is not where Payments believes it is.

**What we expect from Payments** (an instruction is a one-way message; no loss needs both sides)
- Publish through its own transactional outbox, so no instruction is committed but never sent.
- Treat our events as the acknowledgement: every instruction is answered with exactly one event
  carrying its `transfer_id` (ADR 0015); the current account is credited only after `MoneyWithdrawn`.
  No acknowledgement
  within a timeout: alert, or re-send. Re-sending is always safe, with the same or a new message id.
- Reconcile daily: what Payments believes it moved between current account and savings, and what
  Savings booked (ledger entries reference the `transfer_id`; the `transfer` table answers "was
  transfer X applied?"; `transfer-clearing` in the postings should net to zero per transfer). Differences become tickets. Reconciliation catches what the
  other layers can't: bugs, manual fixes, a dead letter nobody replayed.

## Consequences

- `processed_event` and `transfer` grow with traffic. `processed_event` would get a retention window
  longer than any possible redelivery (e.g. 30 days, partitioned by day and dropped); `transfer` is
  business data and is kept as long as the ledger.
- Dead letters need an owner and a runbook: inspect the exception headers, fix the cause (or agree a
  correction with Payments), replay the original bytes to the instructions topic. Replay is safe
  because of both idempotency levels.
- Verified by the acceptance tests "a redelivered message is applied only once", "the same transfer
  re-sent as a new message is credited only once", "a transfer id reused for a different amount …
  is dead-lettered", "a re-sent withdrawal is applied once", the return scenarios, and `DeadLetterIT`
  (which also checks the dead-letter counter).
