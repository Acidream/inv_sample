# savings-platform

A savings-account service in an event-driven architecture: accounts are opened over REST, money
moves in from and out to the customer's current account through instructions from a Payments team on Kafka, interest accrues daily and is
paid monthly. Built to be **correct and auditable** first, and as simple as that allows.

## The big picture

```mermaid
flowchart LR
    Client -- "REST: products, open account,<br/>balance, statement" --> API
    P[Payments team] -- "payments.savings-instructions.v1<br/>TransferFromCurrentAccount<br/>TransferToCurrentAccount<br/>TransferReturned" --> L
    subgraph S [savings-service]
        API[REST API] --> UC[use cases]
        L[instruction listener] --> UC
        J[nightly interest job] --> UC
        UC --> DB[(Postgres<br/>products · accounts · transfers<br/>ledger · outbox · processed events)]
        DB --> R[outbox relay]
    end
    R -- "savings.account-events.v1<br/>AccountOpened · MoneyDeposited<br/>MoneyWithdrawn · WithdrawalRejected<br/>WithdrawalReturned · InterestPaid" --> C[other teams]
    R -- "savings.postings.v1<br/>balanced double-entry<br/>postings" --> F[Finance:<br/>general ledger]
    S -. metrics .-> Prom[Prometheus / Grafana]
```

## Assumptions about the ledger

What the design takes as given about money records. Each is recorded in an ADR, and changing it
would change the design.

- **Savings keeps a customer ledger, not the bank's books.** The `ledger_entry` table is a
  *single-entry* record per account: signed amount, balance after, business reference. It is the
  customer's view and the source of truth for balances, statements and interest. The bank's
  **general ledger is a separate system owned by Finance**; Savings is a sub-ledger that feeds it
  ([ADR 0004](docs/adr/0004-money-and-append-only-ledger.md),
  [0014](docs/adr/0014-double-entry-postings-for-finance.md)).
- **No double-entry bookkeeping inside Savings.** There is no journal, no chart of accounts and no
  trial balance here. Every booking and every daily interest accrual is *published* as a balanced
  double-entry posting on logical book accounts (`customer:<id>`, `transfer-clearing`,
  `interest-expense`, `interest-payable`); Finance maps them to its chart of accounts and keeps the
  books. What Finance can reconcile against Savings: the customer lines of an account sum to that
  account's ledger balance, and `transfer-clearing` nets to zero per transfer
  ([0014](docs/adr/0014-double-entry-postings-for-finance.md)). A full double-entry core inside
  Savings was considered and rejected as more than one product needs.

## What it does

- **Products**: an account is opened on a savings product (`INSTANT_ACCESS`, `QUARTERLY_INTEREST`,
  `YEARLY_INTEREST`) and keeps the terms of the version it was opened on: annual rate and payout
  frequency. New terms are a new version; products come from Flyway reference data.
- **Money only moves between savings and the customer's own current account.** External payments
  are the current account's business, so every transfer has a known outcome at once.
- **Deposits** are credited when Payments reports that the current account was debited.
- **Withdrawals** are debited immediately if the balance allows it, otherwise rejected. If the
  current account then refuses the money, Payments sends a return and Savings credits it back.
  The balance can never go negative.
- **Interest**: ACT/365 on each day's closing balance, accrued nightly with sub-cent precision,
  credited at the end of the product's payout period (month, quarter or year), rounded down to the
  cent, with the remainder carried over.
- **Bookkeeping for Finance**: every booking and every daily accrual is also published as a balanced
  double-entry posting (customer, transfer clearing, interest expense, interest payable), so the
  bank's general ledger can be kept and reconciled from it.

```mermaid
sequenceDiagram
    participant P as Payments
    participant S as Savings
    participant C as Current account
    P->>S: TransferToCurrentAccount
    alt balance suffices
        S-->>P: MoneyWithdrawn (debited at once)
        P->>C: credit
        opt current account refuses
            P->>S: TransferReturned
            S-->>P: WithdrawalReturned (credited back)
        end
    else balance too low
        S-->>P: WithdrawalRejected
    end
```

## Other assumptions

- **Money only moves to and from the customer's own current account**, so every transfer has a
  known outcome at once, and there are no holds and no negative balances
  ([0015](docs/adr/0015-transfers-only-with-the-current-account.md)).
- **Payments keeps its side of the contract:** it keys instructions by account id, publishes through
  its own outbox, treats Savings' events as the acknowledgement and reconciles daily
  ([0007](docs/adr/0007-idempotent-consumer-and-dead-letters.md),
  [0008](docs/adr/0008-ordering-and-concurrency.md)).
- **An account keeps its product version's terms for life**: new rates apply to new accounts only.
  Products differ in rate and payout frequency, not yet in notice periods, terms or rate tiers
  ([0017](docs/adr/0017-savings-products.md)).
- **One currency (EUR), one business time zone (Europe/Berlin)**; no taxes, campaigns, account
  closing or authentication ([0001](docs/adr/0001-scope-savings-only.md)).

## Where the interesting decisions are

| Concern | How | Read |
|---|---|---|
| Exact money | `BigDecimal` value object, strings on the wire, rounding (down) only at payout | [ADR 0004](docs/adr/0004-money-and-append-only-ledger.md) |
| Auditability | Append-only ledger with `balance_after`; daily accrual rows | [0004](docs/adr/0004-money-and-append-only-ledger.md), [0010](docs/adr/0010-interest-accrual-and-payout.md) |
| Books that balance | Every booking published as a double-entry posting, balanced by construction, value-dated | [0014](docs/adr/0014-double-entry-postings-for-finance.md) |
| No lost or phantom events | Transactional outbox, single ordered relay | [0006](docs/adr/0006-transactional-outbox.md) |
| At-least-once delivery | De-duplication by message id and by transfer id in the business transaction; retries; alerted dead-letter topic; what Payments must do so nothing is lost | [0007](docs/adr/0007-idempotent-consumer-and-dead-letters.md) |
| Concurrency | Per-account ordering via partition key, `@Version` optimistic locking, DB constraints, per-account entry numbers independent of instance clocks | [0008](docs/adr/0008-ordering-and-concurrency.md), [0011](docs/adr/0011-ledger-partitioning-and-keyset-pagination.md) |
| Distributed workflow | Transfers with the current account as a saga with one compensating step; why no holds | [0015](docs/adr/0015-transfers-only-with-the-current-account.md) |
| Big tables | Monthly partitions, one composite index, keyset pagination | [0011](docs/adr/0011-ledger-partitioning-and-keyset-pagination.md) |
| Products | Immutable product versions from a catalogue; accounts keep theirs; terms composed, not subclassed | [0017](docs/adr/0017-savings-products.md) |
| Contracts | Protobuf envelope with `oneof`, additive evolution, anti-corruption layer | [0005](docs/adr/0005-protobuf-event-contracts.md) |
| Architecture | Hexagonal layers; plain-Java domain, JPA entities only in the persistence adapter; boundaries enforced by Checkstyle import control and ArchUnit | [0003](docs/adr/0003-hexagonal-layers-with-jpa-on-the-domain.md), [0016](docs/adr/0016-domain-model-separate-from-jpa-entities.md) |
| Delivery | Acceptance tests first, real infrastructure in tests | [0012](docs/adr/0012-acceptance-tests-first.md) |
| Operations | Business metrics, dashboard, alerts | [0013](docs/adr/0013-observability.md) |
| What's left out, and why | Taxes, campaigns, auth, S3, ... | [0001](docs/adr/0001-scope-savings-only.md) |

All decisions: [docs/adr](docs/adr/README.md) · Vocabulary: [docs/GLOSSARY.md](docs/GLOSSARY.md)

## Stack and build

Java 25 · Spring Boot 4.2 · Hibernate 7 · Flyway · PostgreSQL 17 · Kafka 4 · Protobuf ·
Micrometer/Prometheus/Grafana · JUnit 6 · AssertJ · Mockito · Testcontainers · Karate · ArchUnit ·
Checkstyle · GitHub Actions

```
./gradlew build          # Checkstyle, unit, architecture and acceptance tests (needs Docker)
```

## Repository layout

```
events-proto/         Protobuf contracts (inbound from Payments, outbound account events)
savings-service/      the service
  domain/             Account, Transfer, LedgerEntry, Posting, Money, Interest: plain Java, no JPA, no Spring
  application/        use cases and the ports they need
  adapter/            web · messaging · persistence (JPA entities, kept package-private) · scheduling · metrics
  resources/db/       Flyway migrations
  test/.../acceptance Karate runner + TestHooks;  test/resources/acceptance/*.feature
payments-simulator/   CLI that plays the Payments team locally
ops/                  Prometheus config and alerts, Grafana provisioning and dashboard
config/checkstyle/    code style and import-control.xml (which layer may import what)
docs/                 ADRs and glossary
```

## The specification

The acceptance features are the best entry point into the behaviour:

- [products.feature](savings-service/src/test/resources/acceptance/products.feature): products on sale, new versions, accounts keeping theirs
- [accounts.feature](savings-service/src/test/resources/acceptance/accounts.feature): opening on a product, validation, problem details
- [deposits.feature](savings-service/src/test/resources/acceptance/deposits.feature): crediting, redelivery and re-sent transfers applied once, reused transfer id → DLT, paginated statement
- [withdrawals.feature](savings-service/src/test/resources/acceptance/withdrawals.feature): immediate debit, rejection, no double spend, return once, contract violations → DLT
- [interest.feature](savings-service/src/test/resources/acceptance/interest.feature): monthly and quarterly payout, catch-up from closing balances, carried remainders
- [postings.feature](savings-service/src/test/resources/acceptance/postings.feature): double-entry postings for deposits, withdrawals and returns, daily accruals and payouts

## Running it locally

```bash
./gradlew :savings-service:bootJar
docker compose up --build
```

| What | Where |
|---|---|
| API docs | http://localhost:8080/swagger-ui.html |
| Metrics | http://localhost:8080/actuator/prometheus |
| Prometheus (alerts) | http://localhost:9090/alerts |
| Grafana (dashboard "Savings") | http://localhost:3000 |

Play the Payments team:

```bash
ACCOUNT=$(curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' \
  -d '{"customerId":"3f2b8c1a-5d4e-4f6a-9b7c-1a2b3c4d5e6f","productCode":"INSTANT_ACCESS"}' | jq -r .id)

./gradlew -q :payments-simulator:run --args="deposit  $ACCOUNT 250.00"
./gradlew -q :payments-simulator:run --args="withdraw $ACCOUNT 100.00"     # prints transferId=...
./gradlew -q :payments-simulator:run --args="return   $ACCOUNT <transferId>"   # current account refused it

curl -s localhost:8080/accounts/$ACCOUNT
curl -s localhost:8080/accounts/$ACCOUNT/transactions
```

## REST API

| Method | Path | |
|---|---|---|
| `GET` | `/products` | products on sale with their current version: `code`, `version`, `name`, `annualRate`, `payoutFrequency` |
| `POST` | `/accounts` | `{ "customerId": uuid, "productCode": "INSTANT_ACCESS" }` → `201` + `Location`; a product not on sale → `422`. Optional `Idempotency-Key` header: a retry with the same key returns the same account; the key reused for a different body → `422` |
| `GET` | `/accounts/{id}` | balance, accrued interest and the product version it was opened on; amounts as strings |
| `GET` | `/accounts/{id}/transactions?limit=20&cursor=…` | newest first, keyset-paginated, `nextCursor`; each entry has its `entryNo` (1, 2, 3, ... per account) |

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details.
