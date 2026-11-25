# 0028 – Investments as a third ledger in the transfer service

Date: 2026-11-24 · Status: Accepted · Amends [0018](0018-service-structure.md) · For [0026](0026-investment-service.md)

## Context

The investment service pulls each buy's money from the current account and pays refunds and
proceeds back to it (ADR 0026). Money moves between ledgers only through the transfer service
(ADR 0015, 0018), which so far knows two: savings and the current account. It routes by ledger, never
by product, so the question is only how a third ledger joins without the transfer service learning
anything about securities.

## Decision

**Portfolios are accounts of a third ledger, `INVESTMENTS`.** The transfer service learns them from
`PortfolioOpened` on `investments.account-events.v1` into its account directory, exactly as it learns
savings accounts from `AccountOpened`: id, owner, ledger. The same ownership check applies, so a
portfolio's reference account must belong to the portfolio's customer.

**Savings and investments are product ledgers, and take the same instructions.** The transfer
service sends a portfolio the message it sends a savings account, `payments.v1.PaymentInstruction`
(`TransferFromCurrentAccount`, `TransferToCurrentAccount`, `TransferReturned`), on a topic of its own,
`payments.investment-instructions.v1`, keyed by portfolio id. Investments answers each with exactly one
event under the same names savings uses (`MoneyDeposited`, `MoneyWithdrawn`, `WithdrawalRejected`,
`WithdrawalReturned`). In the saga the savings steps became product-ledger steps that carry the ledger
they are for.

**Two new routes, still with the current account on one side**: `CURRENT_TO_INVESTMENTS` (a buy's
funding) and `INVESTMENTS_TO_CURRENT` (a refund or proceeds). A credit to a portfolio is never refused,
as for savings; a credit to the current account can be, and the money then goes back to the portfolio
(ADR 0015's one compensating step), where investments keeps it as an unpaid payout. Savings to
investments and back is not a route: the customer goes through the current account.

## Consequences

- The transfer service still knows nothing about products, orders or securities. A fourth product
  ledger would be another topic and enum value, plus the places that still pick a topic or a route
  by ledger: the saga's product-ledger steps, the routes, the listener and the directory's ledger
  check. No product rule.
- `transfer.route` and `known_account.ledger` widen (`V4__investments_ledger.sql`).
- Verified by `transfers.feature` (buy funding, a refused refund coming back, no savings-to-portfolio
  route) and `TransferTest`.

## Alternatives considered

- **A generic "product ledger" topic shared by savings and investments**, keyed by account. One topic
  fewer, but each team would consume the other's instructions and filter them, and an instruction's
  owner would no longer be clear from where it was sent.
- **Investments as a second kind of savings account** in the transfer service. It would work today and
  mislead the first time the two differ.
