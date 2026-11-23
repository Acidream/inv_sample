-- Every consumed message id is stored prefixed by its source, as platform-messaging's
-- ProcessedMessages asks: "command:", "transfer-event:", and now "instruction:" for payment
-- instructions, which were stored bare. The column gets the width the other services use, so a
-- 100-character event id still fits with its prefix.
alter table processed_message alter column message_id type varchar(200);

update processed_message
set message_id = 'instruction:' || message_id
where message_id not like 'command:%'
  and message_id not like 'transfer-event:%'
  and message_id not like 'instruction:%';

-- Left over from savings' own retention job (V6). The shared RetentionJob deletes old outbox rows in
-- id order, which the primary key serves; the other services never had this index.
drop index if exists outbox_event_published_idx;
