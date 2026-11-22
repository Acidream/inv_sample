-- Transactional outbox: events are written here in the business transaction and relayed to Kafka.
create table outbox_event (
    id           bigserial primary key,
    aggregate_id uuid         not null,
    topic        varchar(200) not null,
    event_type   varchar(100) not null,
    payload      bytea        not null,
    created_at   timestamptz  not null default now(),
    published_at timestamptz
);

create index outbox_event_unpublished_idx on outbox_event (id) where published_at is null;

-- ShedLock: at most one instance runs each scheduled job.
create table shedlock (
    name       varchar(64)  primary key,
    lock_until timestamptz  not null,
    locked_at  timestamptz  not null,
    locked_by  varchar(255) not null
);
