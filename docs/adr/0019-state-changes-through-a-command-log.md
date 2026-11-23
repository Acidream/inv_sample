# 0019 – State changes only through a command log; REST for queries

Date: 2026-11-23 · Status: Accepted

## Context

Savings opened accounts through `POST /accounts`: a state change that left no trace on Kafka. A
service whose inputs are partly on a log and partly in HTTP requests can't be rebuilt, or checked,
by replaying its inputs. The same holds for the transfer service.

## Decision

**Every state change of a service starts as a message on one of its input topics.** REST is for
queries (`GET`), and for *accepting* commands, never for executing them:

```
PUT /orders/{orderId}       ──►  savings.commands.v1          ──►  savings consumes it    ──►  202 Accepted
PUT /transfers/{transferId} ──►  payments.transfer-requests.v1 ──►  transfers consumes it  ──►  202 Accepted
```

- The endpoint checks the request's shape and writes it to the topic; it changes no state. The
  answer is `202 Accepted` with the `Location` of the resource; the outcome is read there
  (`GET`, `404` until the command has been consumed) or from the service's events.
- The client chooses the id (`orderId`, `transferId`), so repeating the request is safe: the consumer
  applies a command for an id once. A repeated `PUT` whose body differs from what was already
  processed is refused with `422`; one that arrives before the first was processed is ignored by the
  consumer and logged.
- Whether a command succeeds is decided by the consumer only, so a replay reaches the same decision.

**The ledger stays the system of record.** Replaying the inputs is for recovery and verification:
rebuilding a lost database, or proving that a new version computes the same history as the booked
ledger by replaying into a shadow database and comparing. History is never re-booked by new code;
an error is corrected by a new entry.

**Replay needs deterministic processing.** Done in this step for the new flows:

- an order's account id and its transfer id are derived from the order id;
- an account opened by an order is opened at the time the order was placed (the command's
  `occurred_at`), and the product version is checked as of that time.

Not done yet, and needed before a replay can be compared byte for byte:

- payment instructions are booked at processing time, not at the message's `occurred_at`;
- event, posting and outbox ids are random; they should be derived from the input message id;
- interest runs on a cron and the wall clock; time should become an input too, a
  `BusinessDayClosed(date)` message that every ledger reacts to;
- input topics need unlimited retention (or tiered storage), and every message ever written must stay
  readable: Protobuf with additive changes only (ADR 0005).

## Consequences

- A client can't learn the outcome from the `PUT` response; it reads the resource or the events.
  For opening an account that is natural anyway: funding it takes a transfer that may fail.
- Commands for one id are ordered: the topics are keyed by order id and transfer id.
- The intake writes to Kafka synchronously; when Kafka is down, the `PUT` fails with `503` and the
  client retries with the same id. Nothing is half-done.

## Alternatives considered

- **REST that executes, plus an audit log of requests**: the log is a copy, and copies drift.
- **Event sourcing** (the log as system of record, state derived from it): replaying with changed
  code could change balances customers have already seen. A booked ledger is the legal record.
