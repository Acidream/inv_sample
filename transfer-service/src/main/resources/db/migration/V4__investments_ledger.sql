-- ADR 0028: the investment service's portfolios are a third ledger, with two routes of their own.
alter table known_account drop constraint known_account_ledger_check;
alter table known_account add constraint known_account_ledger_check
    check (ledger in ('SAVINGS', 'CURRENT_ACCOUNT', 'INVESTMENTS'));

alter table transfer drop constraint transfer_route_check;
alter table transfer alter column route type varchar(30);
alter table transfer add constraint transfer_route_check
    check (route in ('CURRENT_TO_SAVINGS', 'SAVINGS_TO_CURRENT', 'CURRENT_TO_INVESTMENTS', 'INVESTMENTS_TO_CURRENT'));
