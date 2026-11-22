-- Idempotency keys of POST /accounts: a retried request returns the account it already opened.
-- No foreign key to account: the key is inserted with plain JDBC before Hibernate flushes the new
-- account at commit, and both are written in the same transaction anyway.
create table account_open_request (
    idempotency_key varchar(100) primary key,
    account_id      uuid         not null,
    created_at      timestamptz  not null default now()
);

-- Retention (RetentionJob): published outbox rows and old message ids are deleted by age.
create index outbox_event_published_idx on outbox_event (published_at) where published_at is not null;
create index processed_event_processed_at_idx on processed_event (processed_at);
