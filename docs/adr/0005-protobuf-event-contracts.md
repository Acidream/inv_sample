# 0005 – Kafka contracts: Protobuf envelope with `oneof`, keyed by account

Date: 2026-11-22 · Status: Accepted

## Context

Savings consumes instructions from Payments and publishes facts for any interested team. Contracts
must be strongly typed, evolvable without lock-step deployments, and preserve per-account ordering.

## Decision

| Topic | Owner | Message | Key |
|---|---|---|---|
| `payments.savings-instructions.v1` | Payments | `PaymentInstruction` | account id |
| `payments.savings-instructions.v1.DLT` | Savings | original bytes + error headers | account id |
| `savings.account-events.v1` | Savings | `AccountEvent` | account id |
| `savings.postings.v1` | Savings (for Finance) | `Posting` | account id |

- One **envelope** per topic: `event_id`, `occurred_at`, `account_id` and a `oneof payload`. One topic
  per stream (not per event type), so all events of an account are totally ordered on one partition.
- **Key = account id**, part of the contract: producers must key by account (ADR 0008).
- Evolution rules (written in the `.proto` files): additive only, never change or reuse a field
  number, reserve removed numbers, enums start with `*_UNSPECIFIED = 0`. Breaking changes mean a new
  topic version (`.v2`) and a migration period.
- Decimal money as strings (ADR 0004).
- Inbound messages pass an anti-corruption layer (`PaymentInstructionTranslator`) that validates and
  translates them into application commands; the use cases never see Protobuf.

## Alternatives considered

- **Confluent Schema Registry** with compatibility checks: the right tool once several teams publish
  many schemas; it enforces the evolution rules at produce time. Here the shared `events-proto`
  library is the registry, and the next step would be `buf breaking` in CI.
- **JSON events**: easier to read, but no schema, and floats tempt people.
- **Topic per event type**: loses ordering between e.g. `MoneyWithdrawn` and `WithdrawalReturned`.

## Consequences

- Consumers can ignore payload types they don't know (forward compatible).
- Messages are not human-readable on the wire; `JsonFormat` is used in tests and tooling.
