-- Lets the retention job (platform-messaging) find old ids without a full scan.
create index processed_message_processed_at_idx on processed_message (processed_at);
