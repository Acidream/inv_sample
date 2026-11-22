-- Append-only ledger, range-partitioned by month on booked_at.
-- * Statement and balance-at queries always filter by account and time -> index below.
-- * Old months can be detached/archived without touching hot data, and vacuum works per partition.
-- * Postgres requires the partition key in every unique constraint, hence PK (id, booked_at).

create sequence ledger_entry_seq increment by 50;

create table ledger_entry (
    id            bigint         not null,
    account_id    uuid           not null references account (id),
    type          varchar(20)    not null check (type in ('DEPOSIT', 'WITHDRAWAL', 'WITHDRAWAL_RETURN', 'INTEREST')),
    amount        numeric(19, 2) not null,
    balance_after numeric(19, 2) not null,
    reference     varchar(100)   not null,
    booked_at     timestamptz    not null,
    primary key (id, booked_at)
) partition by range (booked_at);

create index ledger_entry_account_booked_idx on ledger_entry (account_id, booked_at desc, id desc);

-- Safety net: should stay empty. Monthly partitions are created ahead by LedgerPartitionMaintenance.
create table ledger_entry_default partition of ledger_entry default;

do $$
declare
    month_start timestamp;
begin
    for i in -1..3 loop
        month_start := date_trunc('month', now() at time zone 'UTC') + make_interval(months => i);
        execute format(
            'create table if not exists %I partition of ledger_entry for values from (%L) to (%L)',
            'ledger_entry_' || to_char(month_start, 'YYYY_MM'),
            month_start at time zone 'UTC',
            (month_start + interval '1 month') at time zone 'UTC');
    end loop;
end
$$;
