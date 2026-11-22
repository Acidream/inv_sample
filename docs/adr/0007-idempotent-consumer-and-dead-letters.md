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
  - Deposits: the `deposit` table has the `payment_id` as primary key. A payment already credited
    with the same account and amount is skipped and counted as a duplicate; one with a different
    account or amount is a contract violation and goes to the DLT. (A unique constraint on
    `ledger_entry.reference` isn't possible: partitioned tables need `booked_at` in every unique key.)
  - Withdrawals: `hold.request_id` is unique; a second `WithdrawalRequested` with a known
    `request_id` is ignored; repeating a hold transition that already happened is a no-op (ADR 0009).

**Failures** (`KafkaConfig`)
- Transient errors (DB unavailable, optimistic-lock conflict with a batch job) are retried in place:
  3 times, 500 ms apart.
- Non-retryable errors go straight to `<topic>.DLT` with the original bytes and exception headers:
  unparseable Protobuf, validation errors, unknown account or withdrawal, contract violations such
  as confirming a cancelled withdrawal.
- A rejected withdrawal (insufficient funds) is **not** an error; it's a normal outcome answered with
  a `WithdrawalRejected` event.
- Every dead letter increments `savings_messages_dead_lettered_total{topic}`; the
  **SavingsInstructionDeadLettered** alert pages on any increase. Parked is not lost, but until someone
  acts, the money is not where Payments believes it is.

**What we expect from Payments** (an instruction is a one-way message; no loss needs both sides)
- Publish through its own transactional outbox, so no instruction is committed but never sent.
- Treat our events as the acknowledgement: a payment is `CREDITED` only when `MoneyDeposited` with
  its `payment_id` arrives; a withdrawal moves money only after `FundsReserved`. No acknowledgement
  within a timeout: alert, or re-send. Re-sending is always safe, with the same or a new message id.
- Reconcile daily: what arrived at the bank (the clearing statement: camt.053 for SEPA, CEP receipts for SPEI), what Payments believes it
  credited, and what Savings booked (ledger entries reference the `payment_id`; the `deposit` table
  answers "was payment X credited?"). Differences become tickets. Reconciliation catches what the
  other layers can't: bugs, manual fixes, a dead letter nobody replayed.

## Consequences

- `processed_event` and `deposit` grow with traffic. `processed_event` would get a retention window
  longer than any possible redelivery (e.g. 30 days, partitioned by day and dropped); `deposit` is
  business data and is kept as long as the ledger.
- Dead letters need an owner and a runbook: inspect the exception headers, fix the cause (or agree a
  correction with Payments), replay the original bytes to the instructions topic. Replay is safe
  because of both idempotency levels.
- Verified by the acceptance tests "a redelivered message is applied only once", "the same payment
  re-sent as a new message is credited only once", "a payment id reused for a different amount …
  is dead-lettered", the withdrawal contract-violation scenario, and `DeadLetterIT` (which also checks
  the dead-letter counter).
