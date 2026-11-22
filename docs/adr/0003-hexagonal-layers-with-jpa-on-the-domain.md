# 0003 – Lightweight hexagonal layers, JPA annotations on the domain

Date: 2026-11-22 · Status: Accepted

## Context

Business rules (money, transfers, interest) must be testable without Spring, Kafka or a database, and
easy to find. Full Clean Architecture (separate persistence models plus mappers for every entity)
doubles the code without adding much for a service this size.

## Decision

```
com.example.savings
├── domain        plain Java: Account, Transfer, LedgerEntry, Posting, Money, Interest, AccountEvent
├── application   use cases + ports (interfaces the use cases need)
├── adapter
│   ├── web          REST controller, DTOs, problem details
│   ├── messaging    Kafka listener, Protobuf translation, outbox publisher and relay
│   ├── persistence  Spring Data repositories implementing the ports, JDBC adapters
│   ├── scheduling   ShedLock-guarded jobs
│   └── metrics      Micrometer implementation of the metrics port
└── config        wiring, properties
```

- **Dependencies point inwards.** Enforced by `ArchitectureTest` (ArchUnit): the domain may not
  touch Spring, Hibernate, Kafka, Protobuf or Micrometer; use cases may not touch wire formats,
  JDBC, web or Kafka.
- **Pragmatic exception:** domain classes carry `jakarta.persistence` annotations. They are
  metadata, not behaviour; the domain still runs in plain unit tests. The price is that some JPA
  constraints leak in (protected no-arg constructors, `@Version` fields).
- Spring Data repositories implement the ports directly through `default` methods
  (`AccountRepository extends JpaRepository<Account, UUID>, Accounts`), which avoids one adapter class
  per port.
- Use cases rely on the persistence context's dirty checking: a loaded `Account` changed inside a
  transaction is saved on commit. That's a JPA-ism visible in the application layer, accepted for
  brevity and documented on the `Accounts` port.
- Packages are by layer because there is a single bounded context. With a second one (e.g.
  campaigns), the top level would become feature packages, each with the same three layers inside.

## Consequences

- Domain tests are fast and dependency-free (`AccountTest`, `TransferTest`, `PostingTest`, `InterestTest`).
- Replacing JPA with plain JDBC would mean writing persistence models, not changing use cases.
