# 0024 – Outcomes checked against own records; invalid messages named; intake outside transactions; dated off-sale

Date: 2026-11-23 · Status: Accepted · Amends [0007](0007-idempotent-consumer-and-dead-letters.md), [0017](0017-savings-products.md), [0019](0019-state-changes-through-a-command-log.md), [0020](0020-open-and-fund-a-savings-product.md)

## Context

A review of the whole platform found six weaknesses, none of which had lost money yet:

- Savings marked an order `FUNDED` or `FUNDING_FAILED` on a transfer event that carries only a
  transfer id. Transfer ids are chosen by whoever requests a transfer, and `PUT /transfers/{id}`
  accepted `savings-order-<orderId>`. A client that took an order's id before savings had applied
  the order would run its own transfer under it: savings' request would then be dead-lettered as a
  reused id, and the client's transfer would fund the order, whatever its amount or target.
- The transfer service accepted a ledger's answer for a transfer by transfer id and ledger, without
  checking that the answer was about the account the instruction went to.
- The REST intakes ran the "already applied?" lookup and the synchronous Kafka send (up to 10 s) in
  one read-only transaction. With virtual threads and a pool of 10 connections, a slow Kafka let
  intake requests hold every connection the consumers, the outbox relay and the queries need.
- Every `IllegalArgumentException` was dead-lettered. Meant for the anti-corruption layer, it also
  parked valid instructions when one came from a bug or a library.
- Taking a product off sale was a plain flag, read when an order was applied. An order placed while
  the product was sold, but applied or replayed after it was taken off sale, was rejected, against
  ADR 0019's rule that an order is decided as of the time it was placed.
- Some ADRs and comments still used names from before ADR 0022.

## Decision

- **An outcome counts only if the service's own records agree.**
  - Savings funds an order only if it booked a deposit itself under the order's transfer id, to the
    order's account, of the order's amount. A failure report is accepted only if savings booked no
    money under that id (a credit to savings is never taken back). Anything else is a contract
    violation (`IllegalOrderTransitionException`, dead-lettered).
  - The transfer service accepts a ledger's answer only from the account the instruction went to:
    the source for a debit or its rejection, the target for a credit or its refusal, the savings
    source for a return. Otherwise `IllegalTransferStateException`, dead-lettered.
  - Transfer ids starting with `savings-` are reserved for savings and refused by
    `PUT /transfers/{id}` with `400`. They are still read through `GET`.
- **Invalid messages are named.** `InvalidMessageException` (platform-messaging) is what the
  anti-corruption layers throw, and what `DeadLetters` treats as never succeeding, next to
  `InvalidProtocolBufferException` and each service's own contract violations: savings'
  `IllegalTransferException` (now also for a reused transfer id and a return for another account),
  the transfer service's and the current account's `TransferIdInUseException`, the current account's
  `PaymentIdInUseException`. A plain `IllegalArgumentException` is retried, and **ConsumerBlocked**
  reports a partition it blocks.
- **The intake is not transactional.** The lookup is one statement and needs no transaction; the
  Kafka send then holds no connection while it waits.
- **Taking a product off sale is dated.** `savings_product.off_sale_from` replaces `on_sale` (V11):
  a product is on sale at `t` if the column is null or `t < off_sale_from`. Like a version it can be
  published ahead of the day it takes effect, and a trigger refuses to change it once set. Products
  that were already off sale count as off sale from the migration.

## Consequences

- The real transfer service reports an outcome only after savings has answered, so the order check
  never waits for a deposit in normal operation. A test that plays the transfer service must do the
  same: wait for `MONEY_DEPOSITED` before sending `TransferCompleted`.
- A completion report savings can't match parks the event; the order stays `FUNDING` and
  **MessageDeadLettered** pages. That is the intended outcome for an order whose funding is in doubt.
- A message that fails with an exception nobody classified now blocks its partition instead of being
  parked. That is safer for money and louder for people; the fix is to classify the exception.
- Verified by the acceptance scenarios "a transfer reported completed without the deposit booked in
  savings funds nothing", "an answer for another account under the transfer's id is dead-lettered and
  moves nothing", "transfer ids of savings' orders can't be taken through the API" and "an order is
  decided as of when it was placed, also when the product went off sale since", and by the domain
  tests of `Order` and the transfer service's `Transfer`.
