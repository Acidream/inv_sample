-- Savings products (ADR 0017). A customer opens an account on a product; the product's terms live in
-- versions. A version's terms never change once published: new terms are a new version, and an
-- account keeps the version it was opened on for its whole life. New products and versions come in
-- through migrations like this one, so every change to what the bank sells is reviewed and versioned.

create table savings_product (
    code    varchar(40)  primary key check (code ~ '^[A-Z][A-Z0-9_]*$'),
    name    varchar(100) not null,
    -- Taking a product off sale stops new accounts; existing accounts keep their terms.
    on_sale boolean      not null
);

-- The current version of a product is the one with the latest valid_from that has passed, so a rate
-- change can be published ahead of the day it takes effect.
create table savings_product_version (
    product_code     varchar(40)   not null references savings_product (code),
    version          int           not null check (version > 0),
    annual_rate      numeric(7, 4) not null check (annual_rate >= 0),
    payout_frequency varchar(10)   not null check (payout_frequency in ('MONTHLY', 'QUARTERLY', 'YEARLY')),
    valid_from       timestamptz   not null,
    primary key (product_code, version)
);

create function refuse_product_version_change() returns trigger language plpgsql as $$
begin
    raise exception 'savings_product_version % v% is published and cannot change; publish a new version',
        old.product_code, old.version;
end
$$;

create trigger savings_product_version_immutable
    before update or delete on savings_product_version
    for each row execute function refuse_product_version_change();

insert into savings_product (code, name, on_sale) values
    ('INSTANT_ACCESS', 'Instant access savings, interest paid monthly', true),
    ('QUARTERLY_INTEREST', 'Instant access savings, interest paid quarterly', true),
    ('YEARLY_INTEREST', 'Instant access savings, interest paid yearly', true);

insert into savings_product_version (product_code, version, annual_rate, payout_frequency, valid_from) values
    ('INSTANT_ACCESS', 1, 0.0250, 'MONTHLY', '2025-01-01 00:00:00 Europe/Berlin'),
    ('QUARTERLY_INTEREST', 1, 0.0275, 'QUARTERLY', '2025-01-01 00:00:00 Europe/Berlin'),
    ('YEARLY_INTEREST', 1, 0.0300, 'YEARLY', '2025-01-01 00:00:00 Europe/Berlin');

-- An account's terms are those of the product version it was opened on. Not in production yet, so
-- there are no existing accounts to carry over.
alter table account
    drop column annual_rate,
    add column product_code    varchar(40) not null,
    add column product_version int         not null,
    add constraint account_product_version_fk
        foreign key (product_code, product_version) references savings_product_version (product_code, version);
