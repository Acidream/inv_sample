-- Every transfer between the customer's current account and this savings account, under the id the
-- Payments team gave it:
-- * business-key idempotency: a transfer re-sent as a new message is applied once (processed_event
--   only catches redeliveries of the same message);
-- * the state a return needs (only a completed withdrawal can come back, and only once);
-- * the lookup for reconciliation with Payments ("was transfer X applied?").
create table transfer (
    transfer_id varchar(100)   primary key,
    account_id  uuid           not null references account (id),
    direction   varchar(3)     not null check (direction in ('IN', 'OUT')),
    amount      numeric(19, 2) not null check (amount > 0),
    status      varchar(10)    not null check (status in ('COMPLETED', 'REJECTED', 'RETURNED')),
    created_at  timestamptz    not null,
    returned_at timestamptz,
    version     bigint         not null,
    check (status <> 'RETURNED' or (direction = 'OUT' and returned_at is not null))
);

create index transfer_account_idx on transfer (account_id);
