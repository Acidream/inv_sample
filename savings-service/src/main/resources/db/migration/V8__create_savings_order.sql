-- Accounts are opened by orders now (ADR 0020); POST /accounts and its idempotency keys are gone.
drop table account_open_request;

-- A customer's order to open an account on a product version and fund it (ADR 0020).
-- No foreign key to account: the order row is written with plain JDBC before Hibernate flushes the
-- new account at commit; both are written in the same transaction.
create table savings_order (
    order_id         uuid           primary key,
    customer_id      uuid           not null,
    product_code     varchar(40)    not null,
    product_version  int            not null,
    amount           numeric(19, 2) not null check (amount > 0),
    from_account_id  uuid           not null,
    placed_at        timestamptz    not null,
    status           varchar(20)    not null check (status in ('REJECTED', 'FUNDING', 'FUNDED', 'FUNDING_FAILED')),
    rejection_reason varchar(40),
    account_id       uuid,
    transfer_id      varchar(100)   unique,
    failure_reason   varchar(300),
    version          bigint         not null,
    check ((status = 'REJECTED') = (rejection_reason is not null)),
    check ((status = 'REJECTED') = (account_id is null and transfer_id is null))
);

create index savings_order_customer_idx on savings_order (customer_id);

-- Messages are keyed by more than account ids now: transfer requests are keyed by transfer id.
alter table outbox_event rename column aggregate_id to message_key;
alter table outbox_event alter column message_key type varchar(100) using message_key::text;
