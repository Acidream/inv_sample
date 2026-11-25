# 0008 – Ordering by partition key, safety by optimistic locking

Date: 2026-11-22 · Status: Accepted · Investments and the FIX gateway lock rows instead: [0026](0026-investment-service.md), [0027](0027-fix-gateway.md)

## Context

Two writers touch an account concurrently: the Kafka consumer (instructions) and the nightly
interest job. Lost updates on a balance are unacceptable; locks that serialize hot
accounts are expensive.

## Decision

1. **Ordering comes from Kafka.** Payments keys instructions by account id (part of the contract,
   ADR 0005), so all instructions of one account are on one partition and are consumed by one thread
   in order. "Return before withdrawal" cannot happen.
2. **Safety comes from optimistic locking.** The stored account and transfer have `@Version`
   (`AccountEntity`, `TransferEntity`); the domain objects carry the version as an opaque token
   (ADR 0016). A conflicting commit fails with `OptimisticLockingFailureException` instead of
   overwriting.
   - Consumer: the error handler retries the whole message (new transaction, fresh read), see ADR 0007.
   - Interest job: skips the account; the next run catches it up because accrual resumes from the
     account's last accrued day (ADR 0010).
3. **Invariants are also enforced by the database**: `check (balance >= 0)` on accounts,
   `check (amount > 0)` and primary key `transfer_id` on transfers, primary key
   `(account_id, accrual_date)` on accruals.
4. **Jobs run on one instance** (ShedLock, lock in Postgres using DB time).

## Alternatives considered

- `SELECT … FOR UPDATE` on every instruction: simple and correct, but holds row locks across the
  whole use case and serializes the batch jobs behind live traffic. Conflicts are rare here (one
  consumer thread per account, jobs touch each account once a day), which is exactly when
  optimistic locking wins.
- Serializable isolation: same guarantees, more retries, harder to reason about.

## Consequences

- No pessimistic locks on the hot path.
- Retries must be safe, and they are: each attempt is a full transaction including the
  de-duplication marker.
