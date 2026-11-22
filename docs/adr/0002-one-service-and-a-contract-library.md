# 0002 – One service plus a shared contract library

Date: 2026-11-22 · Status: Accepted

## Context

The team runs "multiple microservices and supporting library projects". With only savings in scope
(ADR 0001), splitting further (e.g. a separate interest service) would create distributed
transactions around the balance — the one thing that must stay consistent.

## Decision

A Gradle multi-module repository:

- `savings-service` – the deployable Spring Boot application, owner of all savings data.
- `events-proto` – Protobuf schemas for the topics Savings consumes and produces. Both producer and
  consumers depend on it, like a team's contract library.
- `payments-simulator` – a tiny CLI that plays the Payments team in local demos.

The Payments team's service is not built; tests and the simulator produce its messages.

## Consequences

- One transactional boundary per account: balance, transfers, ledger, outbox live in one database.
- The contract is versioned and reviewed separately from the service that implements it.
- In a real setup `events-proto` would be its own repository/artifact, published to a registry and
  consumed by version; a module is the simplest equivalent here.
