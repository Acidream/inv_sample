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
- **One composite index** `(account_id, booked_at desc, id desc)`, created on the parent and inherited
  by each partition. It serves the statement, the keyset predicate and `balanceAt`.
- **Partitions are created ahead**: Flyway creates last month through three months ahead;
  `LedgerPartitionMaintenance` runs at startup and daily to keep three months ahead. A `DEFAULT`
  partition catches anything else and should stay empty: rows there block creating the matching
  monthly partition, so it deserves an alert.
- **Keyset pagination** for statements: the opaque cursor encodes `(booked_at, id)` of the last row;
  the next page is `where (booked_at, id) < cursor order by booked_at desc, id desc limit n`. Constant
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
