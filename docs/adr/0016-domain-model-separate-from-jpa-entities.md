# 0016 – Domain model separate from JPA entities, boundaries checked on every build

Date: 2026-11-23 · Status: Accepted · Supersedes the "pragmatic exception" and dirty-checking parts of [0003](0003-hexagonal-layers-with-jpa-on-the-domain.md)

## Context

ADR 0003 put `jakarta.persistence` annotations on `Account`, `Transfer` and `LedgerEntry` to save the
mapping code. In practice the persistence model kept shaping the domain:

- the domain needed protected no-arg constructors, mutable fields and `BigDecimal` columns where
  `Money` belongs;
- use cases relied on Hibernate's dirty checking, so "is this change saved?" was answered by the
  persistence context, not by the code you read;
- nothing stopped an entity, a Spring Data repository or JPA itself from being used outside the
  persistence adapter. ArchUnit only checked that the domain avoided Spring and Hibernate, and
  `jakarta.persistence` was explicitly allowed there.

For a ledger whose main promise is being auditable, what gets written, and when, should be visible
in the use case.

## Decision

**Plain domain.** `Account`, `Transfer` and `LedgerEntry` are plain Java: final fields where they
can be, `Money` instead of `BigDecimal`, invariants checked in the constructor. Persistence reads and
rebuilds them through an explicit `State` record (`Account.State`, `Transfer.State`) or
`LedgerEntry.restore(...)`; rebuilding checks the same invariants as creating (e.g. no negative
balance, only a withdrawal can be returned).

**Entities in the adapter.** `AccountEntity`, `TransferEntity`, `LedgerEntryEntity` and their Spring
Data repositories are package-private in `adapter.persistence`. `JpaAccounts`, `JpaTransfers` and
`JpaLedger` implement the ports and map between entity and domain object.

**Explicit saves.** The ports have `add` (new) and `update` (changed); use cases call `update` for
every aggregate they changed. Nothing is saved implicitly.

**Optimistic locking stays.** The aggregates carry `version`, an opaque concurrency token the
domain never interprets (null until first stored). `update` refuses a change made to another version
than the stored one, and Hibernate's `@Version` on the entity catches a concurrent commit between
read and write (`where version = ?`). Both raise `OptimisticLockingFailureException`, handled as
before (ADR 0008).

**Boundaries checked twice, on every build:**

| Rule | Checkstyle `ImportControl` (source) | ArchUnit (bytecode) |
|---|---|---|
| Domain imports only `java.*` and itself | ✓ | ✓ |
| Use cases: domain, ports, `@Service`/`@Component`/`@Transactional`, SLF4J | ✓ | ✓ (no wire formats, JDBC, web, Kafka, Micrometer) |
| JPA, Hibernate, Spring Data and the persistence adapter's own classes only inside `adapter.persistence` | ✓ | ✓ |
| `@Entity` classes live in `adapter.persistence` and are not public | | ✓ |

Rules are in `config/checkstyle/import-control.xml` and `ArchitectureTest`. Checkstyle also sees
imports that compile away (an unused annotation import); ArchUnit also sees fully qualified uses
that never appear as imports.

## Alternatives considered

- **Keep JPA on the domain** (ADR 0003): less code, but the persistence model keeps leaking in, and
  saving stays implicit.
- **Spring Data JDBC** with the domain as the aggregate: no dirty checking, less mapping, but a
  second persistence stack next to the JPA one the partitioned ledger and the Flyway-validated schema
  already use.
- **ArchUnit only**: enough to fail the build, but import control points at the offending line in
  the IDE and in review, before the code even compiles into a test run.

## Consequences

- About 400 more lines, tests included: three entities, three port implementations, two `State` records.
- The schema didn't change; the entities map the same tables and columns.
- A use case that forgets `update` loses its change. `PaymentInstructionHandlerTest` checks the
  calls, and the acceptance tests would fail on any lost balance.
