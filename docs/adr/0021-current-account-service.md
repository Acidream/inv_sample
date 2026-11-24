# 0021 – The current account service

Date: 2026-11-23 · Status: Accepted · Amends [0018](0018-service-structure.md) · Amended by [0022](0022-shared-technical-library.md)

## Context

ADR 0018 cut the platform into three services but built only two: the current account existed as
a contract, played by the tests and by `current-account-simulator`. The simulator answered the
transfer service with fixed rules (credits always succeed, debits above one configurable amount
are rejected). So a demo could not show where a refusal really comes from: a balance, an overdraft
limit, a blocked account. And the whole flow (salary in, savings funded, money back) never ran
against three real ledgers.

## Decision

**Build `current-account-service`, the third service of ADR 0018, and remove the simulator.** It
owns current accounts and their ledger in its own database and speaks only through Kafka. The
contracts the transfer service already uses (`payments.current-account-instructions.v1` in,
`current-account.events.v1` out) stay as they were, so the transfer service doesn't change.

**The rules for money leaving a current account live here.**

- A debit is carried out if the balance after it is not below minus the account's overdraft
  limit; otherwise `DebitRejected` ("overdraft limit exceeded"). The limit is set when the account
  is opened.
- A blocked account refuses transfers both ways ("account blocked"). Blocking and unblocking are
  published (`CurrentAccountBlocked`, `CurrentAccountUnblocked`), so a payout refused while the account
  was blocked can be retried once it isn't (ADR 0026). If it refuses a credit, the
  transfer service gives the money back to savings (ADR 0015's one compensating step).
- Money arriving from outside the bank (`ReceivePayment`, e.g. a salary) is always booked, even on a
  blocked account, because it has already arrived. A payment for an unknown account is rejected.
- An instruction for an unknown account is answered with a rejection, not dead-lettered: the
  transfer service then fails the transfer cleanly.

**Inputs follow ADR 0019.** Opening, incoming payments and blocking or unblocking go through the
service's own command log, `current-account.commands.v1` (keyed by account id). REST only appends
and answers `202`; the client chooses the account id and the payment id. **Bookings are dated
when the input was issued** (the command's or the instruction's `occurred_at`), not when it was
processed. Savings still books at processing time (a gap ADR 0019 lists); this service doesn't
have that gap. Its event ids are still random.

**Idempotency on two levels, as elsewhere (ADR 0007).** The message id catches redeliveries. The
transfer id (per account) and the payment id catch the same thing sent again: each instruction's
answer is stored, carried out or not, and a repeat is skipped. A transfer id or payment id reused
for something different is a contract violation and goes to the dead-letter topic.

**Concurrency.** Commands and instructions for one account arrive on different topics and are
consumed concurrently, so each transaction locks the account row (`select … for update`) before it
reads the balance. Unlike in the transfer service, no advisory lock is needed: an account that
doesn't exist yet has no balance to protect, and its instructions are rejected.

**Ledger.** A single-entry, append-only ledger as in savings (ADR 0004): signed amount, balance
after, reference (transfer id or payment id), unique per account, type and reference. The balance
is also kept on the account row for the overdraft check, and a database constraint guarantees it
never goes below minus the limit.

**The technical library is not extracted in this change.** ADR 0018 named a third copy of the
outbox, the idempotent consumer and `Money` as the point to extract them. This service copies them
once more, so that building it doesn't also change the two services that already work. Extracting
the library is the next step and touches all three services in one change of its own.

## Consequences

- The full flow runs locally against three real ledgers: open a current account, receive a salary,
  order a savings product funded from it, move money back. Overdraft and blocking produce the
  refusals the saga handles.
- The current account publishes no double-entry postings for Finance yet (savings does, ADR 0014).
  So `transfer-clearing` can't yet be reconciled across services; that needs the current account's
  postings.
- There are no overdraft interest, fees or statements, and no way to change a limit or close an
  account. A blocked account can be unblocked.
- No holds or reservations: a debit happens at once or is refused, which ADR 0015 relies on.
- The outbox, relay, idempotent consumer and `Money` exist three times until the library is
  extracted.

## Alternatives considered

- **Keep the simulator, with more rules.** It would grow into a second implementation of the
  current account without its data or tests.
- **Reserve, then capture** (a hold on debit, booked when the transfer completes): needed for
  payments whose outcome comes later, not for transfers whose outcome is known at once (ADR 0015).
- **Answer instructions for unknown accounts with a dead letter.** The transfer service only sends
  instructions for accounts it learnt from `CurrentAccountOpened`, so this "can't happen", but if it
  does, a rejection fails the transfer cleanly, while a dead letter leaves it stuck.
