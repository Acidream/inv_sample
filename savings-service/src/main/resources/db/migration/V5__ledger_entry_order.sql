-- Booking order that doesn't depend on instance clocks (ADR 0011):
-- * ledger_entry.entry_no numbers each account's entries 1, 2, 3, ... in booking order;
-- * account.last_entry_no / last_booked_at let the aggregate hand out the next number and keep
--   booked_at from going backwards when a booking comes from an instance whose clock is behind.
-- Statements and balance-at-T order by (booked_at, entry_no) instead of (booked_at, id): the id comes
-- from a sequence of which every instance reserves its own block, so it says nothing about order.

alter table ledger_entry add column entry_no bigint;

update ledger_entry e
set entry_no = numbered.entry_no
from (select id, booked_at,
             row_number() over (partition by account_id order by booked_at, id) as entry_no
      from ledger_entry) numbered
where e.id = numbered.id and e.booked_at = numbered.booked_at;

alter table ledger_entry alter column entry_no set not null;
alter table ledger_entry add constraint ledger_entry_entry_no_positive check (entry_no > 0);

alter table account
    add column last_entry_no  bigint      not null default 0,
    add column last_booked_at timestamptz;

update account a
set last_entry_no  = coalesce((select max(e.entry_no) from ledger_entry e where e.account_id = a.id), 0),
    last_booked_at = coalesce((select max(e.booked_at) from ledger_entry e where e.account_id = a.id),
                              a.opened_at);

alter table account alter column last_booked_at set not null;
alter table account alter column last_entry_no drop default;

drop index ledger_entry_account_booked_idx;
create index ledger_entry_account_booked_idx on ledger_entry (account_id, booked_at desc, entry_no desc);

-- Interest monitoring: savings_interest_accrual_lag_days reads min(last_accrual_date) on every scrape.
create index account_last_accrual_date_idx on account (last_accrual_date);
