# 0011 – Monthly range partitions and keyset pagination for the ledger

Date: 2026-11-22 · Status: Accepted

## Context

`ledger_entry` is the largest and fastest-growing table: every deposit, withdrawal and monthly
interest payout of every account, kept for years (German retention periods for books and booking
records are 8 to 10 years). Reads are always "one account, newest first" or "one account, balance before T".

## Decision

- **Range-partition by `booked_at`, one partition per month.** Postgres requires the partition key in
  every unique constraint, so the primary key is `(id, booked_at)`. Hibernate only needs `id` to be
  unique, which the sequence guarantees.
- **Booking order is (`booked_at`, `entry_no`)**, never the database id. `entry_no` numbers each
  account's entries 1, 2, 3, ... (the account keeps `last_entry_no`), and the account never books an
  entry earlier than its previous one (`last_booked_at`). Bookings for one account can come from
  different instances (after a Kafka rebalance, or from the interest job), and their clocks differ by
  a few milliseconds; without the clamp a later entry could sort before the one whose balance it builds
  on, and `balanceAt` would return a stale balance. The id says nothing about order, because every
  instance reserves its own block of 50 sequence values. A unique key on (account_id, entry_no) isn't
  possible on the partitioned table; the account's `@Version` ensures each number is handed out once.
- **One composite index** `(account_id, booked_at desc, entry_no desc)`, created on the parent and
  inherited by each partition. It serves the statement, the keyset predicate and `balanceAt`.
- **Partitions are created ahead**: Flyway creates last month through three months ahead;
  `LedgerPartitionMaintenance` runs at startup and daily to keep three months ahead. A `DEFAULT`
  partition catches anything else and should stay empty: rows there block creating the matching
  monthly partition, so it deserves an alert.
- **Keyset pagination** for statements: the opaque cursor encodes `(booked_at, entry_no)` of the last
  row; the next page is `where (booked_at, entry_no) < cursor order by booked_at desc, entry_no desc
  limit n`. Constant
  cost per page, stable under concurrent inserts, unlike `OFFSET`, which scans and discards every
  skipped row.
- Hibernate schema validation can't see partitioned tables by default:
  `hibernate.hbm2ddl.extra_physical_table_types=PARTITIONED TABLE`.

## Consequences

- Old months can be detached and archived (e.g. to S3 as Parquet) without `DELETE` and vacuum storms.
- Autovacuum and index maintenance work per partition; the hot partition stays small.
- Queries without a time bound touch every partition's index. Acceptable for per-account lookups,
  which only scan a few index entries in each partition.
- The same treatment would apply to `interest_accrual` (one row per account per day) and
  `processed_event` once volumes justify it.
