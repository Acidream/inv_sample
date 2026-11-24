-- The investment service (ADR 0026): portfolios with a money ledger that is empty at rest, orders,
-- holdings as FIFO lots, and payouts to the customer's current account.

-- Reference data: what can be bought. Traded in EUR only.
create table instrument (
    isin          char(12)     primary key check (isin ~ '^[A-Z]{2}[A-Z0-9]{9}[0-9]$'),
    name          varchar(200) not null,
    type          varchar(10)  not null check (type in ('ETF', 'STOCK')),
    off_sale_from timestamptz
);

create table portfolio (
    portfolio_id         uuid           primary key,
    customer_id          uuid           not null,
    reference_account_id uuid           not null,
    cash                 numeric(19, 2) not null check (cash >= 0),
    last_entry_no        bigint         not null check (last_entry_no >= 0),
    opened_at            timestamptz    not null
);

create index portfolio_reference_account_idx on portfolio (reference_account_id);

-- The portfolio's money ledger, append-only (ADR 0004). Trade details for fills.
create table cash_entry (
    portfolio_id  uuid           not null references portfolio,
    entry_no      bigint         not null check (entry_no > 0),
    type          varchar(20)    not null check (type in ('FUNDING', 'BUY', 'SELL', 'PAYOUT', 'PAYOUT_RETURN')),
    amount        numeric(19, 2) not null check (amount <> 0),
    balance_after numeric(19, 2) not null check (balance_after >= 0),
    reference     varchar(150)   not null,
    order_id      varchar(50),
    isin          char(12),
    quantity      numeric(24, 6),
    price         numeric(19, 4),
    realized_gain numeric(19, 2),
    booked_at     timestamptz    not null,
    primary key (portfolio_id, entry_no),
    unique (portfolio_id, type, reference),
    check ((type in ('BUY', 'SELL')) = (isin is not null and quantity is not null and price is not null)),
    check ((type = 'SELL') = (realized_gain is not null))
);

-- One row per order, under the id derived from the customer's idempotency key. No foreign keys to
-- portfolio and instrument: an order for an unknown one is kept as REJECTED.
create table investment_order (
    order_id         varchar(50)    primary key,
    portfolio_id     uuid           not null,
    isin             varchar(12)    not null,
    side             varchar(4)     not null check (side in ('BUY', 'SELL')),
    amount           numeric(19, 2) check (amount > 0),
    quantity         numeric(24, 6) check (quantity > 0),
    status           varchar(20)    not null check (status in
        ('FUNDING', 'PLACED', 'PARTIALLY_FILLED', 'FILLED', 'EXPIRED', 'REJECTED', 'FUNDING_FAILED')),
    settlement       varchar(10)    not null check (settlement in ('PENDING', 'SETTLED', 'UNPAID')),
    trading_day      date,
    filled_quantity  numeric(24, 6) not null check (filled_quantity >= 0),
    filled_amount    numeric(19, 2) not null check (filled_amount >= 0),
    rejection_reason varchar(300),
    accepted_at      timestamptz    not null,
    check ((side = 'BUY') = (amount is not null)),
    check ((side = 'SELL') = (quantity is not null)),
    check (side = 'BUY' or filled_quantity <= quantity),
    check (side = 'SELL' or filled_amount <= amount)
);

create index investment_order_portfolio_idx on investment_order (portfolio_id, accepted_at desc);
create index investment_order_trading_idx on investment_order (trading_day)
    where status in ('PLACED', 'PARTIALLY_FILLED');

-- Executions as the broker reported them, once per exec id.
create table order_fill (
    order_id      varchar(50)    not null references investment_order,
    exec_id       varchar(100)   not null,
    seq           bigserial,
    quantity      numeric(24, 6) not null check (quantity > 0),
    price         numeric(19, 4) not null check (price > 0),
    amount        numeric(19, 2) not null check (amount > 0),
    realized_gain numeric(19, 2),
    traded_at     timestamptz    not null,
    primary key (order_id, exec_id)
);

-- What a portfolio holds of an instrument; the units are the sum of its lots.
create table holding (
    portfolio_id uuid           not null references portfolio,
    isin         char(12)       not null references instrument,
    reserved     numeric(24, 6) not null check (reserved >= 0),
    last_price   numeric(19, 4),
    primary key (portfolio_id, isin)
);

-- One lot per buy fill, sold first in, first out. remaining_cost keeps 10 fraction digits.
create table lot (
    id                 bigserial      primary key,
    portfolio_id       uuid           not null,
    isin               char(12)       not null,
    order_id           varchar(50)    not null,
    exec_id            varchar(100)   not null,
    quantity           numeric(24, 6) not null check (quantity > 0),
    remaining_quantity numeric(24, 6) not null check (remaining_quantity >= 0 and remaining_quantity <= quantity),
    remaining_cost     numeric(29, 10) not null check (remaining_cost >= 0),
    acquired_at        timestamptz    not null,
    foreign key (portfolio_id, isin) references holding,
    unique (order_id, exec_id)
);

create index lot_open_idx on lot (portfolio_id, isin, id) where remaining_quantity > 0;

-- Money a portfolio owes its reference account. Each attempt is its own transfer.
create table payout (
    payout_id      uuid           primary key,
    portfolio_id   uuid           not null references portfolio,
    order_id       varchar(50)    unique references investment_order,
    reason         varchar(20)    not null check (reason in ('REFUND', 'PROCEEDS', 'UNEXPECTED_DEPOSIT')),
    amount         numeric(19, 2) not null check (amount > 0),
    status         varchar(10)    not null check (status in ('REQUESTED', 'PAID', 'UNPAID')),
    attempt        int            not null check (attempt > 0),
    transfer_id    varchar(100)   not null unique,
    unpaid_since   timestamptz,
    created_at     timestamptz    not null,
    check ((reason = 'UNEXPECTED_DEPOSIT') = (order_id is null)),
    check (status <> 'UNPAID' or unpaid_since is not null)
);

create index payout_unpaid_idx on payout (unpaid_since) where status = 'UNPAID';

-- The answer to each instruction of the transfer service, so the same one is answered once.
create table transfer_answer (
    portfolio_id     uuid           not null,
    transfer_id      varchar(100)   not null,
    kind             varchar(10)    not null check (kind in ('DEPOSIT', 'WITHDRAWAL', 'RETURN')),
    amount           numeric(19, 2) not null check (amount > 0),
    rejection_reason varchar(300),
    answered_at      timestamptz    not null,
    primary key (portfolio_id, transfer_id, kind),
    check (rejection_reason is null or kind = 'WITHDRAWAL')
);

-- Idempotent consumer: ids of messages already applied, prefixed by their kind.
create table processed_message (
    message_id   varchar(200) primary key,
    processed_at timestamptz  not null default now()
);

create index processed_message_processed_at_idx on processed_message (processed_at);

-- Transactional outbox: events, transfer requests, broker orders and postings.
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
