# 0018 – Service structure: savings, transfers, current account

Date: 2026-11-23 · Status: Accepted · Supersedes [0002](0002-one-service-and-a-contract-library.md) · Current account built in [0021](0021-current-account-service.md) · Technical library: [0022](0022-shared-technical-library.md)

## Context

The platform grows beyond savings: customers move money between their current account and savings
products, and current accounts are to be built too. ADR 0015 already assumes one ledger per product
and a Payments team that orchestrates transfers between them; until now that team's service existed
only as a simulator.

Two questions: how to cut the services, and how much each one knows about the others.

## Decision

**Three services, each owning its data and its ledger, connected only through Kafka contracts.**

| Service | Owns | Knows nothing about |
|---|---|---|
| `savings-service` | Savings products, orders, savings accounts and their ledger, interest | Current accounts beyond an account id |
| `transfer-service` (Payments team) | Transfers: the saga that moves money between two accounts of the same customer | Products, rates, interest, overdraft rules |
| current account (not built yet) | Current accounts, their ledger, overdraft limit, holds | Savings products |

**The transfer service moves money; it does not sell products.** It knows of an account only its id,
its owner and which ledger holds it, learnt from `AccountOpened` and `CurrentAccountOpened` events
into its own account directory, never by calling another service. Its interface is generic: "move
this amount from account A to account B of customer C", whoever asks. Whether a product allows a
withdrawal is the product's answer (`WithdrawalRejected`), not the transfer service's knowledge, so
new products never change it.

**Savings stays one service, built as three modules.** `catalogue` (products and versions), `orders`
(opening and funding an account, ADR 0020) and `accounts` (ledger, interest, postings). `accounts`
depends only on the product's terms (`SavingsProduct`), never on catalogue tables or order classes;
the foreign key from an account to its product version is the one documented link between modules.
ArchUnit checks that the account model doesn't depend on orders.

Savings is split into two services only when one of these holds:

1. another team owns products or sales and releases on its own schedule;
2. the catalogue serves more than savings (term deposits, current account plans): then it is a
   bank-wide product catalogue service;
3. selling grows rules of its own (identity checks, consents, eligibility, campaigns) that change far
   more often than the ledger should;
4. sales and the ledger need different availability.

The split would then move catalogue and orders out; the accounts service keeps an append-only copy
of the product versions it needs (they never change), and nothing else in it moves.

**The current account service is not built in this step.** Its contract is defined
(`payments.current-account-instructions.v1`, `current-account.events.v1`) and played by the tests and
by `current-account-simulator`, as the Payments team used to be.

**Repository layout.**

```
events-proto/               contracts, one package per owning team (savings.v1, payments.v1, current_account.v1)
savings-service/
transfer-service/
current-account-simulator/  plays the current account in local demos
```

## Consequences

- An internal transfer is a saga across two ledgers with compensation (ADR 0015), not a local
  transaction. Each step is idempotent by transfer id, as in savings.
- Finance gets postings from every ledger; `transfer-clearing` nets to zero per transfer *across*
  services, which becomes a real reconciliation check.
- The technical building blocks (outbox and relay, idempotent consumer, `Money`) are copied into the
  transfer service rather than shared. With the current account service they would exist three
  times: that is the point to extract a technical library (no domain rules in it), not earlier.
- `events-proto` stays the contract library; in a real setup it would be one artifact per team,
  published to a registry.

## Alternatives considered

- **One central ledger for all accounts** (rejected in ADR 0015): a transfer would be one local
  transaction, but every product team would share one schema and release train.
- **A modular monolith** with one module per service: simpler to operate, but it gives up the
  independent deployment and data ownership this platform is meant to show.
- **Transfers know products** (the instruction names the product to open or fund): every new product
  rule would change the transfer service.
