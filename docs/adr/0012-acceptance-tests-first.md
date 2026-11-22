# 0012 – Acceptance tests first: Karate + Testcontainers

Date: 2026-11-22 · Status: Accepted

## Context

Continuous delivery needs a test suite you trust enough to deploy on green. The interesting
behaviour here crosses REST, Kafka, Postgres, time and scheduled jobs; mocks would test the mocks.

## Decision

Test pyramid, written outside-in:

| Level | Tooling | What | Where |
|---|---|---|---|
| Acceptance (ATDD) | Karate features, run by JUnit inside `@SpringBootTest` | Every user-visible behaviour, end to end: REST in, Kafka in and out, jobs | `src/test/resources/acceptance/*.feature` |
| Integration | JUnit 5, Testcontainers | Technical behaviour not worth a feature (poison message → DLT) | `DeadLetterIT` |
| Use case | JUnit 5, Mockito at the ports | Rules in the use cases (duplicates, reused ids, returns) | `PaymentInstructionHandlerTest` |
| Domain | JUnit 5, AssertJ, parameterized tables | Money, interest maths, transfer states, balanced postings | `domain/*Test` |
| Architecture | ArchUnit | The dependency rule | `ArchitectureTest` |

- One Spring context with **real Postgres 17 and Kafka 4** (`TestcontainersConfiguration`,
  `@ServiceConnection`), shared by all integration tests. `./gradlew build` needs only Docker.
- `TestHooks` is the bridge Karate calls (`Java.type`): it plays the Payments team (produces
  Protobuf instructions), observes `savings.account-events.v1` and the DLT, moves a `MutableClock`
  and triggers jobs. Cron schedules are disabled in tests; time is explicit in the features.
- Features assert on **both** sides of the contract: REST state and published events.
- Synchronisation without sleeps: wait for the event that proves a message was processed, and rely on
  per-partition ordering to prove an earlier message (e.g. a duplicate) was processed too.

## Consequences

- Features read as the specification ("a withdrawal the current account refused is credited back,
  once").
- The suite takes about 30 s, mostly container start-up, paid once per build.
- Karate 2.1 cannot resolve `classpath:` feature paths on Windows, so the runner uses the filesystem
  path `src/test/resources/acceptance`, which works everywhere.
