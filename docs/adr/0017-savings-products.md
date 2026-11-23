# 0017 – Savings products: versioned terms from a catalogue

Date: 2026-11-23 · Status: Accepted

## Context

Until now every account was the same product: the client sent an `annualRate` when opening an
account, and interest was paid monthly. A bank sells several savings products (instant access
with monthly interest, better rates for less frequent payout, later notice accounts and term
deposits), changes their rates over time, and must be able to say exactly which terms each
customer agreed to.

## Decision

**A product catalogue in the domain.** `SavingsProduct` is one *version* of a product: code,
version, name, annual rate and `PayoutFrequency` (MONTHLY, QUARTERLY, YEARLY). Products are
composed of terms, not modelled as `Account` subclasses: "quarterly with a tiered rate" is a
combination of terms, not a new class.

**Versions are immutable.** A published version never changes; a database trigger refuses updates
and deletes. New terms are a new version with a `valid_from`, so a rate change can be published
ahead of the day it takes effect. The *current* version of a product is the one with the latest
`valid_from` that has passed. A product can be taken off sale (`on_sale = false`), which stops new
accounts and changes nothing for existing ones.

**An account keeps the version it was opened on, for life.** `account` stores
`(product_code, product_version)` with a foreign key into the catalogue. That is the customer's
contract. Moving existing accounts to new terms (a rate change for existing customers) is
deliberately not part of this decision (see below).

**The catalogue is reference data, published by Flyway migrations.** Each change to what the
bank sells is reviewed in a pull request and versioned in git. There is no admin API: it would
need authorisation, which is out of scope (ADR 0001). `GET /products` lists what is on sale.

**Payout follows the product.** Interest still accrues daily (ADR 0010). It is paid after the last
day of the product's payout period has been accrued: the month, the calendar quarter or the
calendar year, all in Europe/Berlin. The period is named `2026-01`, `2026-Q1` or `2026` in the
ledger reference (`interest:<period>`) and in `InterestPaid`, which also carries the period's first
and last day.

**Contracts change additively.**
- `POST /accounts` takes `productCode` instead of `annualRate`; the rate comes from the product. A
  product that doesn't exist or isn't sold is a `422`.
- Account responses carry the product version and its terms.
- `AccountOpened` gains `product_code`, `product_version` and `payout_frequency`; `annual_rate`
  stays, set from the version.
- `InterestPaid` gains `period_start` and `period_end`. `period` is no longer always a year-month:
  a consumer that parses it as one must use the new fields for quarterly and yearly products.

## The ledger is unchanged

Still one single-entry customer ledger per account, and still balanced postings for Finance (ADR
0004, 0014). Products don't move money between each other: every transfer still goes through the
current account (ADR 0015). Book accounts are not split per product; Finance can attribute
interest cost to products through `AccountOpened`, which names each account's product.

## Alternatives considered

- **Rate per account, as before.** Simplest, but no record of what was offered, and every client
  of the API decides rates.
- **Subclass per product** (`InstantAccessAccount`, `QuarterlyAccount`, JPA inheritance). The
  number of classes grows with every combination of terms, and an account's type could never
  change.
- **Copy the terms onto the account.** Robust against catalogue changes, but those can't happen
  anyway (versions are immutable), and a copy loses the link to the product the customer chose.
- **Admin REST API for the catalogue.** Faster for the business, but needs authentication, an
  approval step and an audit trail of its own: a migration has all three for free.

## Consequences

- Restoring an account reads its product version; versions are cached in memory, which is safe
  because they never change.
- Not yet possible, and each would need its own decision:
  - **rate changes for existing accounts** (variable-rate products): a rate history per product
    whose accounts follow it, and accrual picking the rate valid on each day;
  - **notice accounts**: an accepted withdrawal paid out on a later date;
  - **term deposits**: a maturity date, early-withdrawal rules and a decision at maturity;
  - **tiered rates** and deposit limits, which would also need a way to reject a deposit.
