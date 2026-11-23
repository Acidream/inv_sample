# 0020 – Opening a savings product: one order that opens the account, then asks for the money

Date: 2026-11-23 · Status: Accepted

## Context

A customer who wants to put money into a savings product shouldn't have to open an account first
and fund it second. The customer chooses a product, accepts its terms (one product version, ADR
0017) and names an amount and the current account it comes from.

The transfer service must not learn about products (ADR 0018), and the terms the customer accepted
must be the terms the account gets.

## Decision

**Savings owns the order.** `OpenAndFund` (order id, customer, product code and version, amount, the
current account the money comes from) arrives on `savings.commands.v1` (ADR 0019). In one
transaction savings:

1. checks that the product is on sale and the requested version is its current one, as of the time
   the order was placed; otherwise the order is `REJECTED` and nothing else happens;
2. opens the account on that version, so the terms are fixed before any money moves;
3. publishes `AccountOpened` (with the order id) and a `TransferRequest` to the transfer service:
   from the customer's current account to the new savings account;
4. records the order as `FUNDING`.

The transfer then runs as any other (ADR 0015): the current account is debited, savings is credited
(`MoneyDeposited`). Savings follows the outcome on `payments.transfer-events.v1`:
`TransferCompleted` makes the order `FUNDED`, `TransferFailed` makes it `FUNDING_FAILED`.

```
REJECTED ◄── order placed ──► FUNDING ──► FUNDED
                                  └─────► FUNDING_FAILED   (account stays open, balance 0)
```

**A deposit is never refused for product reasons**, because the account and its terms exist before
the money moves. The contract between transfers and savings stays as it was.

**Every order opens its own account.** A customer topping up an account they already have asks the
transfer service for a transfer to it; no order is needed.

**Ids are derived from the order id** (ADR 0019): the account id is a name-based UUID of the order
id, the transfer id is `savings-order-<order id>`. A redelivered or replayed order produces the same
account and the same transfer request, which the transfer service applies once.

## Consequences

- An account whose funding failed stays open with a zero balance for good: its `AccountOpened` is
  published and the transfer service keeps it in its directory. Nothing removes these accounts;
  closing them after a grace period is left for when closing accounts exists (ADR 0001).
- `POST /accounts` is gone: accounts are only opened by orders.
- Order outcomes are read with `GET /orders/{id}`; order events for push notifications can be added
  when an app needs them.

## Alternatives considered

- **Open the account on the first deposit** (the transfer instruction names the product): the
  transfer service would have to know products, and a deposit could be refused because the version
  was no longer sold, which needs a new compensation path.
- **The transfer service runs the whole flow**: it would need to understand opening rules and terms.
