# 0004 – Money as BigDecimal, append-only ledger with cached balance

Date: 2026-11-22 · Status: Accepted

## Context

Balances must be exact, every change explainable after the fact, and the current balance cheap to
read and check on every withdrawal.

## Decision

**Money**
- `Money` is a value object over `BigDecimal` with exactly 2 fraction digits; constructing it with
  more throws instead of silently rounding. EUR only (ADR 0001).
- Accrued interest uses 10 fraction digits and is rounded **half-even** (banker's rounding) once, at
  payout. The remainder is carried, never dropped.
- Amounts cross every boundary as decimal **strings**: in Protobuf (`common.v1.Money.amount`) and in
  JSON (`"balance": "100.00"`), because many JSON clients parse numbers as binary floats.

**Ledger**
- `ledger_entry` is append-only: signed amount, `balance_after`, business reference, `booked_at`. No
  update or delete is exposed (`LedgerEntryRepository` extends the bare `Repository`).
- `account.balance` and `account.held_amount` are a **cached projection**, updated in the same
  transaction as the entry, guarded by `@Version`. Invariant: `account.balance` equals
  `balance_after` of the account's latest entry.
- `balance_after` on every entry makes each row a self-contained audit record and lets
  "balance at time T" be one indexed lookup (needed for interest, ADR 0010).

## Alternatives considered

- **Full double-entry bookkeeping** (every booking debits one account and credits another, including
  internal GL accounts such as "interest expense" and "payments clearing"). This is what a production
  core-banking ledger should do: it makes the books balance by construction and supports
  reconciliation with the general ledger. Not as the internal source of truth: with a single
  customer-facing account type, it adds a second entry per booking without changing any behaviour.
  Instead, every booking is published to Finance as a balanced posting (amended by
  [ADR 0014](0014-double-entry-postings-for-finance.md)).
- **Balance computed from the ledger on every read.** Always correct but O(entries); the cached
  balance plus `balance_after` keeps reads O(1) without losing auditability.

## Consequences

- An auditor can replay any account from its entries; a reconciliation job could check the
  invariant above.
- Posting is one insert plus one update; `@Version` conflicts are the price of the cache (ADR 0008).
