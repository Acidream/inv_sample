create table account (
    id                uuid primary key,
    customer_id       uuid           not null,
    annual_rate       numeric(7, 4)  not null check (annual_rate >= 0),
    balance           numeric(19, 2) not null,
    held_amount       numeric(19, 2) not null check (held_amount >= 0),
    accrued_interest  numeric(19, 10) not null,
    last_accrual_date date           not null,
    opened_at         timestamptz    not null,
    version           bigint         not null
);

create index account_customer_idx on account (customer_id);

-- Funds earmarked for a withdrawal (two-phase: reserve, then confirm or cancel).
create table hold (
    id          uuid primary key,
    account_id  uuid           not null references account (id),
    request_id  varchar(100)   not null unique,
    amount      numeric(19, 2) not null check (amount > 0),
    status      varchar(16)    not null check (status in ('RESERVED', 'SETTLED', 'RELEASED', 'EXPIRED')),
    reserved_at timestamptz    not null,
    expires_at  timestamptz    not null,
    resolved_at timestamptz,
    version     bigint         not null
);

-- The expiry job only ever looks at open holds; a partial index stays tiny.
create index hold_reserved_expiry_idx on hold (expires_at) where status = 'RESERVED';

-- One row per account and day: the audit trail of interest, and a guard against accruing twice.
create table interest_accrual (
    account_id   uuid            not null references account (id),
    accrual_date date            not null,
    amount       numeric(19, 10) not null,
    primary key (account_id, accrual_date)
);

-- Idempotent consumer: ids of messages already applied.
create table processed_event (
    event_id     varchar(100) primary key,
    processed_at timestamptz  not null default now()
);
