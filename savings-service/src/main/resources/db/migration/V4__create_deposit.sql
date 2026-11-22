-- Business-key idempotency for deposits: a payment is credited at most once, whatever message id it
-- arrives under (processed_event only catches redeliveries of the same message). Also the place to
-- look up "was payment X credited?" when reconciling with Payments.
-- A unique constraint on ledger_entry.reference is not possible: the ledger is partitioned and every
-- unique constraint there must include booked_at.
create table deposit (
    payment_id  varchar(100)   primary key,
    account_id  uuid           not null references account (id),
    amount      numeric(19, 2) not null check (amount > 0),
    event_id    varchar(100)   not null,
    received_at timestamptz    not null
);

create index deposit_account_idx on deposit (account_id);
