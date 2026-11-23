-- ADR 0023.
-- * received_at: when the transfer service applied the request. The awaiting-accounts timeout counts
--   from here, not from requested_at (the requester's clock, possibly long before a backlog or a
--   replay delivered the request).
-- * status_changed_at: when the status last changed. A transfer that waits for a ledger's answer
--   longer than transfers.stuck-after is reported by the transfers.stuck gauge.
alter table transfer
    add column received_at       timestamptz,
    add column status_changed_at timestamptz;

update transfer set received_at = requested_at, status_changed_at = now();

alter table transfer
    alter column received_at set not null,
    alter column status_changed_at set not null;

drop index transfer_awaiting_since_idx;
create index transfer_awaiting_since_idx on transfer (received_at) where status = 'AWAITING_ACCOUNTS';

create index transfer_in_flight_idx on transfer (status_changed_at)
    where status in ('DEBITING', 'CREDITING', 'RETURNING');
