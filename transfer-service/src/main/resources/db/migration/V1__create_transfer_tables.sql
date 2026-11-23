-- Accounts the transfer service knows, learnt from AccountOpened (savings) and CurrentAccountOpened
-- (current account) events. Only what routing and ownership checks need: never balances or products.
create table known_account (
    account_id  uuid        primary key,
    customer_id uuid        not null,
    ledger      varchar(20) not null check (ledger in ('SAVINGS', 'CURRENT_ACCOUNT')),
    known_since timestamptz not null default now()
);

-- One row per transfer, under the id its requester chose. The saga's state (ADR 0015, 0018).
create table transfer (
    transfer_id     varchar(100)   primary key,
    customer_id     uuid           not null,
    from_account_id uuid           not null,
    to_account_id   uuid           not null,
    amount          numeric(19, 2) not null check (amount > 0),
    requested_at    timestamptz    not null,
    status          varchar(20)    not null
        check (status in ('AWAITING_ACCOUNTS', 'DEBITING', 'CREDITING', 'RETURNING', 'COMPLETED', 'FAILED')),
    route           varchar(20)    check (route in ('CURRENT_TO_SAVINGS', 'SAVINGS_TO_CURRENT')),
    failure_reason  varchar(20)    check (failure_reason in ('INVALID_REQUEST', 'DEBIT_REJECTED', 'CREDIT_REFUSED')),
    failure_detail  varchar(300),
    version         bigint         not null,
    check ((status = 'FAILED') = (failure_reason is not null))
);

-- Transfers waiting for an account to become known are looked up when it does.
create index transfer_awaiting_from_idx on transfer (from_account_id) where status = 'AWAITING_ACCOUNTS';
create index transfer_awaiting_to_idx on transfer (to_account_id) where status = 'AWAITING_ACCOUNTS';
create index transfer_awaiting_since_idx on transfer (requested_at) where status = 'AWAITING_ACCOUNTS';

-- Idempotent consumer: ids of messages already applied, prefixed by the topic they came from.
create table processed_message (
    message_id   varchar(200) primary key,
    processed_at timestamptz  not null default now()
);

-- Transactional outbox: instructions and transfer events, written with the state change.
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
