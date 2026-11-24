-- The FIX gateway (ADR 0027): orders placed once per order id, executions published once per exec id,
-- and QuickFIX/J's session state, so sequence numbers and the resend log survive restarts.

create table broker_order (
    order_id    varchar(50)    primary key,
    isin        char(12)       not null,
    side        varchar(4)     not null check (side in ('BUY', 'SELL')),
    cash_amount numeric(19, 2) check (cash_amount > 0),
    quantity    numeric(24, 6) check (quantity > 0),
    trading_day date           not null,
    status      varchar(10)    not null check (status in ('RECEIVED', 'SENDING', 'SENT', 'ACCEPTED', 'DONE')),
    received_at timestamptz    not null,
    check ((side = 'BUY') = (cash_amount is not null)),
    check ((side = 'SELL') = (quantity is not null))
);

create index broker_order_to_send_idx on broker_order (received_at) where status = 'RECEIVED';
create index broker_order_open_idx on broker_order (trading_day) where status in ('SENDING', 'SENT', 'ACCEPTED');

-- Executions the broker reported, once per order and exec id: resends and PossDupFlag copies stop here.
create table broker_execution (
    order_id    varchar(50)  not null references broker_order,
    exec_id     varchar(100) not null,
    reported_at timestamptz  not null,
    primary key (order_id, exec_id)
);

-- QuickFIX/J's JdbcStore: one row per session, and the messages it sent, for resend requests.
create table sessions (
    beginstring       char(8)     not null,
    sendercompid      varchar(64) not null,
    sendersubid       varchar(64) not null,
    senderlocid       varchar(64) not null,
    targetcompid      varchar(64) not null,
    targetsubid       varchar(64) not null,
    targetlocid       varchar(64) not null,
    session_qualifier varchar(64) not null,
    creation_time     timestamp   not null,
    incoming_seqnum   integer     not null,
    outgoing_seqnum   integer     not null,
    primary key (beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid,
                 session_qualifier)
);

create table messages (
    beginstring       char(8)     not null,
    sendercompid      varchar(64) not null,
    sendersubid       varchar(64) not null,
    senderlocid       varchar(64) not null,
    targetcompid      varchar(64) not null,
    targetsubid       varchar(64) not null,
    targetlocid       varchar(64) not null,
    session_qualifier varchar(64) not null,
    msgseqnum         integer     not null,
    message           text        not null,
    primary key (beginstring, sendercompid, sendersubid, senderlocid, targetcompid, targetsubid, targetlocid,
                 session_qualifier, msgseqnum)
);

-- Idempotent consumer, outbox and job lock, as in every service (ADR 0006, 0007, 0022).
create table processed_message (
    message_id   varchar(200) primary key,
    processed_at timestamptz  not null default now()
);

create index processed_message_processed_at_idx on processed_message (processed_at);

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
