# 0010 – Daily ACT/365 accrual from end-of-day balances, monthly payout

Date: 2026-11-22 · Status: Accepted

## Context

Interest must be exact to the cent over a month, reproducible for audits, and robust against
missed or repeated job runs, over a table with potentially millions of accounts.

## Decision

**Calculation** (`domain.Interest`, `Account.accrueDay`, `Account.payOutInterest`)
- Day count **ACT/365 Fixed**: each calendar day earns `end-of-day balance × annual rate ÷ 365`, also
  in leap years. Balances can't be negative (ADR 0015); a zero balance earns nothing.
- Days are calendar days in **Europe/Berlin**. The end-of-day balance is read from the ledger
  (`balance_after` of the last entry before midnight Berlin time), not from the current balance.
- Accruals are kept with 10 fraction digits in `interest_accrual` (one row per account and day: the
  audit trail, and a primary key that makes double accrual impossible).
- After the last day of a month is accrued, the accrued amount is **rounded down to cents and
  credited**; the sub-cent remainder stays accrued for the next month. Accrued interest is therefore
  never negative: nothing is paid before it is earned (ADR 0004).

**Execution** (`InterestAccrualJob`, `InterestService`)
- Nightly at 00:15 Berlin time, on one instance (ShedLock).
- Walks accounts due for accrual in **keyset-paginated chunks** of 500 (`where id > :last order by id`),
  one transaction per account, so locks stay short. A failure affects only its account: the job
  logs it, counts it (`savings_interest_accrual_failed_total`) and continues with the next account;
  the next run retries it.
- **Catch-up:** each account accrues every day from its `last_accrual_date + 1` through yesterday.
  Because each day uses that day's closing balance from the ledger, a missed night accrues the same
  amounts for the same days, with one exception: a payout made during the catch-up (see the known
  simplification below). Running twice is a no-op.
- Optimistic-lock conflicts with live traffic skip the account until the next run (ADR 0008).
- **Monitoring** (ADR 0013): `savings_interest_accrual_lag_days` is the number of complete days the
  account furthest behind has not accrued yet. It is 0 during the day and 1 from midnight until the run
  finishes; **SavingsInterestBehind** pages when it stays above 0 for 6 h (a run that never happened,
  or an account that fails every night). **SavingsInterestAccrualFailed** warns on any failure.

## Consequences

- One ledger lookup per account and day, served by the `(account_id, booked_at desc)` index.
- Known simplification: during catch-up, a payout is booked at processing time rather than value
  dated at the start of the next month, so the days between month end and the catch-up run don't see
  the interest credit in their balance. Fix: a `value_date` on ledger entries.
- Scaling path if one sequential job gets too slow: split the id range across workers (several
  ShedLock names or a work queue); the per-account transaction design already allows it.
- Rate changes need a rate history (ADR 0001).
