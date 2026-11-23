/**
 * Technical building blocks for the services' messaging (ADR 0022). No domain rules and no
 * contracts: nothing here imports a service or {@code com.example.events}.
 *
 * <p>A service that uses it owns these tables in its own database and creates them with its own
 * Flyway migrations:
 *
 * <pre>
 * create table outbox_event (
 *     id           bigserial    primary key,
 *     message_key  varchar(100) not null,
 *     topic        varchar(200) not null,
 *     event_type   varchar(100) not null,
 *     payload      bytea        not null,
 *     created_at   timestamptz  not null default now(),
 *     published_at timestamptz
 * );
 * create index outbox_event_unpublished_idx on outbox_event (id) where published_at is null;
 *
 * create table processed_message (
 *     message_id   varchar(...) primary key,   -- as long as the service's longest prefixed id
 *     processed_at timestamptz  not null default now()
 * );
 * create index processed_message_processed_at_idx on processed_message (processed_at);
 *
 * create table shedlock (
 *     name varchar(64) primary key, lock_until timestamptz not null,
 *     locked_at timestamptz not null, locked_by varchar(255) not null
 * );
 * </pre>
 */
package com.example.platform.messaging;
