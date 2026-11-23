-- Current accounts and their ledger (ADR 0021). The balance is kept on the account for the overdraft
-- check; the bookings are the record of how it got there.
create table current_account (
    account_id      uuid           primary key,
    customer_id     uuid           not null,
    overdraft_limit numeric(19, 2) not null check (overdraft_limit >= 0),
    balance         numeric(19, 2) not null,
    status          varchar(10)    not null check (status in ('OPEN', 'BLOCKED')),
    block_reason    varchar(300),
    opened_at       timestamptz    not null,
    check (balance >= -overdraft_limit),
    check ((status = 'BLOCKED') = (block_reason is not null))
);

-- Append-only ledger (ADR 0004). One booking per transfer or incoming payment and account.
create table booking (
    id            bigserial      primary key,
    account_id    uuid           not null references current_account,
    reference     varchar(100)   not null,
    type          varchar(20)    not null check (type in ('TRANSFER_DEBIT', 'TRANSFER_CREDIT', 'INCOMING_PAYMENT')),
    amount        numeric(19, 2) not null check (amount <> 0),
    balance_after numeric(19, 2) not null,
    booked_at     timestamptz    not null,
    unique (account_id, type, reference)
);

create index booking_account_idx on booking (account_id, id desc);

-- The answer to each instruction of the transfer service, carried out or not, so that the same
-- transfer asked again is answered once. No foreign key: unknown accounts are answered too.
create table transfer_answer (
    account_id       uuid           not null,
    transfer_id      varchar(100)   not null,
    direction        varchar(10)    not null check (direction in ('DEBIT', 'CREDIT')),
    amount           numeric(19, 2) not null check (amount > 0),
    rejection_reason varchar(300),
    answered_at      timestamptz    not null,
    primary key (account_id, transfer_id)
);

-- Money from outside the bank, under the payment id the sender chose.
create table incoming_payment (
    payment_id       varchar(100)   primary key,
    account_id       uuid           not null,
    amount           numeric(19, 2) not null check (amount > 0),
    status           varchar(10)    not null check (status in ('BOOKED', 'REJECTED')),
    rejection_reason varchar(300),
    received_at      timestamptz    not null,
    check ((status = 'REJECTED') = (rejection_reason is not null))
);

-- Idempotent consumer: ids of messages already applied, prefixed by their kind.
create table processed_message (
    message_id   varchar(200) primary key,
    processed_at timestamptz  not null default now()
);

-- Transactional outbox: events, written with the state change.
create table outbox_event (
    id           bigserial    primary key,
    message_key  varchar(100) not null,
    topic        varchar(200) not null,
    event_type   varchar(100) not null,
    payload      bytea        not null,
    created_at   timestamptz  not null default now(),
    published_at timestamptz
);

create index outbox_event_unpublished_idx on outbox_event (id) where published_at is null;

create table shedlock (
    name       varchar(64)  primary key,
    lock_until timestamptz  not null,
    locked_at  timestamptz  not null,
    locked_by  varchar(255) not null
);
