# 0014 – Savings publishes balanced double-entry postings for Finance

Date: 2026-11-22 · Status: Accepted · Amends [0004](0004-money-and-append-only-ledger.md)

## Context

The customer ledger (ADR 0004) is single-entry: a deposit credits the customer's account and records
nothing about where the money came from. That is enough to run savings accounts, but the bank's books
need both sides of every movement. Savings is a sub-ledger; the general ledger (GL) belongs to
Finance and has to reconcile with it every day.

Account events (`savings.account-events.v1`) don't do this job well:
- they describe customer-facing facts, not bookings, and holds produce events but aren't bookings;
- daily interest accruals aren't published at all, yet accrual accounting needs the interest expense
  in the period it was earned, not only when it is paid out;
- a GL service deriving postings from them would have to re-implement savings rules.

## Decision

**Savings publishes a balanced posting for every booking**, on `savings.postings.v1`
(`events-proto/.../savings/v1/postings.proto`), through the same transactional outbox (ADR 0006).

| Booking | Debit | Credit |
|---|---|---|
| Deposit | `payments-clearing` | `customer:<id>` |
| Withdrawal settled (also a late confirmation) | `customer:<id>` | `payments-clearing` |
| Daily interest accrual (sub-cent) | `interest-expense` | `interest-payable` |
| Monthly interest payout (cents) | `interest-payable` | `customer:<id>` |
| Hold reserved, released, expired | no posting: no money moved | |

- **Balanced by construction.** `domain.Posting` can't be built unless debits equal credits; lines
  carry positive amounts and a side. The acceptance hooks re-check every published posting.
- **Logical book accounts** (`domain.BookAccount`), not GL codes. Finance maps them to its chart of
  accounts, so Savings changes nothing when Finance restructures its accounts.
- **One way to book.** `application.Bookkeeping` is the only code path that appends a ledger entry or
  records an accrual, and it publishes the posting in the same transaction. The customer ledger and
  the postings can't diverge.
- **Value date** on every posting: the business day (Europe/Berlin) it belongs to in the books. The
  accrual for 31 January is computed after midnight but lands in January.
- Accrual lines keep 10 fraction digits. The sub-cent remainder that payouts leave behind stays in
  `interest-payable`, matching `account.accrued_interest`.
- Keyed by account id, at-least-once, `posting_id` for de-duplication: the same rules as account
  events (ADR 0005, 0007).

## Alternatives considered

- **Finance derives postings from account events.** Savings stays unaware of accounting, but the
  accounting meaning of savings behaviour (holds aren't bookings, late confirmations are, accruals
  exist) would live in another team's code, and accrual events would have to be added anyway.
- **Full double-entry ledger inside Savings** (a journal table as the source of truth, customer
  balances derived from it). The textbook core-banking design; more than one product needs. Here the
  customer ledger stays the source of truth and postings are its bank-wide projection.
- **Daily batch file** of postings (e.g. to S3). Common in traditional banks and simple, but a day
  late and not event-driven.

## Consequences

- Invariants Finance can check, per account and in total:
  `−Σ customer:<id> postings = account balance` and
  `Σ accruals − Σ payouts = Σ accrued interest`.
- Volume: one accrual posting per account per day. With millions of accounts, the next step is one
  aggregated accrual posting per day for the GL, with per-account detail staying in
  `interest_accrual`.
- Not built yet: daily **control totals** (sum of balances and of accrued interest as of end of day)
  for Finance to reconcile against. They need a clean cut-off, i.e. the ledger's `balance_after` at
  midnight per account, not a snapshot of `account.balance` taken while bookings continue.
- Corrections stay additive: a reversal is a new posting with the sides swapped, never an update.
- Verified by `postings.feature` (deposit, settled withdrawal with holds producing nothing, daily
  accruals with value dates, payout) and `PostingTest`.
