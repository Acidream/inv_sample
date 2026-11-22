# 0001 – Scope: savings accounts only

Date: 2026-11-22 · Status: Accepted

## Context

The investment domain covers savings accounts, campaigns (rewards), securities and taxation. A
sample built in a couple of days can either touch all of them superficially or do one of them
properly. "Financial-grade, correct and auditable" is about depth, not breadth.

## Decision

Build **savings accounts** end to end: opening, deposits, two-phase withdrawals, daily interest
accrual and monthly payout. Deliberately left out:

| Omitted | Why it's safe to leave out | What it would take |
|---|---|---|
| **Taxes** (Abgeltungsteuer, Soli, Kirchensteuer, Freistellungsauftrag) | Interest is paid gross; no other part depends on it. | A per-customer, per-tax-year allowance (Freistellungsauftrag, up to €1,000 since 2023) consumed at payout, withholding 25 % + 5.5 % Soli on the rest, in the payout transaction. Interest would then be booked as two entries (gross credit, tax debit). |
| **Campaigns / bonus interest** | Would be a separate consumer of `savings.account-events.v1`. | A campaign service that reacts to `AccountOpened`, `InterestPaid`. |
| **Investments** | Different aggregate (orders, holdings, FIFO cost basis). | A separate service. |
| **S3** | Nothing needs object storage yet. | Monthly statements as PDF/CSV exports. |
| **Authentication/authorisation** | Orthogonal to the domain; would mostly be configuration. | JWT validation at a gateway or Spring Security resource server, customer id taken from the token rather than the request. |
| **Multi-currency** | EUR-only product. `Money` hard-codes EUR and the wire format already carries a currency code, which is validated. | Currency on `Money` and per account. |
| **Rate changes / rate history** | One fixed rate per account. | A `rate_period` table and accrual picking the rate valid on each day. |
| **Closing accounts** | Not needed to demonstrate the flows. | Close with final interest payout, reject further instructions. |

## Consequences

- Every feature that exists is tested end to end; nothing is half-built.
- The omissions are explicit, so a reviewer can tell "not done" from "not thought of".
