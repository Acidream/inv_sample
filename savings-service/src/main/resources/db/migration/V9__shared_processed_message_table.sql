-- The idempotent consumer is shared by all services now (platform-messaging, ADR 0022) and expects
-- the same table name in each of them.
alter table processed_event rename to processed_message;
alter table processed_message rename column event_id to message_id;
alter index processed_event_pkey rename to processed_message_pkey;
alter index processed_event_processed_at_idx rename to processed_message_processed_at_idx;
