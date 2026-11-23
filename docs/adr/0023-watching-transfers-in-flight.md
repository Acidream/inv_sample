# 0023 – Watching transfers in flight

Date: 2026-11-23 · Status: Accepted · Amends [0007](0007-idempotent-consumer-and-dead-letters.md), [0013](0013-observability.md), [0018](0018-service-structure.md)

## Context

A review of the transfer service found four ways a transfer could go wrong without anyone being
told:

- A transfer in `DEBITING`, `CREDITING` or `RETURNING` waited forever if a ledger never answered,
  e.g. because the instruction or the answer was dead-lettered. ADR 0007 asks Payments to alert when
  an instruction isn't acknowledged in time; the transfer service is now ours, and it didn't.
- The awaiting-accounts timeout counted from `requested_at`, the requester's time. A request
  delivered late (a backlog in savings, a replay of a command log, ADR 0019) arrived already
  expired and could fail as `INVALID_REQUEST` before its account's opening event was consumed.
- The account directory kept the first announcement of an account id and silently dropped any
  other. Current account ids are chosen by clients and savings account ids can be computed from an
  order id, so one id can be claimed by two ledgers or two customers.
- Only savings had a consumer-lag alert, while `DeadLetters` relies on one to report a partition
  blocked by a message retried without end.

## Decision

- **Stuck transfers are counted, not re-sent.** The transfer table records `status_changed_at`; the
  gauge `transfers.stuck` counts transfers waiting for a ledger's answer longer than
  `transfers.stuck-after` (15 minutes), and **TransferStuck** pages. A re-send would not help: both
  ledgers answer a transfer id exactly once, so a repeated instruction is skipped without an answer.
  A stuck transfer means a dead letter or a bug, which needs a person anyway.
- **The awaiting-accounts timeout counts from `received_at`**, when the transfer service applied
  the request. `requested_at` stays what the requester said.
- **An account id announced with a different owner or ledger is a conflict.** `AccountConflictException`
  dead-letters the event, so `MessageDeadLettered` pages; the directory keeps the first entry.
- **Consumer alerts cover every service.** **ConsumerBlocked** (page) fires when a consumer's lag
  hasn't been zero once in 15 minutes; **ConsumerLag** (warn) replaces `SavingsConsumerLag`.
- **Consumers of events skip unknown payloads.** Adding an event type is an additive change (ADR
  0005); a consumer built on the older contract sees it as an unset payload. Events are skipped,
  instructions and commands are still dead-lettered: those must be understood.

## Consequences

- `received_at` and `status_changed_at` come from the service's `Clock`, like the times they are
  compared with, so tests can move time.
- The two new transfer columns are persistence details; the domain model doesn't carry them.
- An unknown event type is skipped silently (debug log). A ledger that starts sending events a
  transfer depends on must be released after the transfer service understands them.
