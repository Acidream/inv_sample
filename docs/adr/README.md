# Architecture Decision Records

Each record captures one decision: the forces at play, what was chosen, and what it costs.
Format: [Michael Nygard](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

| # | Decision | Status |
|---|---|---|
| [0001](0001-scope-savings-only.md) | Scope: savings accounts only, conscious omissions | Accepted |
| [0002](0002-one-service-and-a-contract-library.md) | One service plus a shared Protobuf contract library | Superseded by 0018 |
| [0003](0003-hexagonal-layers-with-jpa-on-the-domain.md) | Lightweight hexagonal layers, JPA annotations allowed on the domain | Partly superseded by 0016 |
| [0004](0004-money-and-append-only-ledger.md) | Money as BigDecimal, append-only ledger with cached balance | Accepted |
| [0005](0005-protobuf-event-contracts.md) | Kafka contracts: Protobuf envelope with `oneof`, keyed by account | Accepted |
| [0006](0006-transactional-outbox.md) | Publish events through a transactional outbox | Accepted |
| [0007](0007-idempotent-consumer-and-dead-letters.md) | Idempotent consumer, retries, dead-letter topic | Accepted |
| [0008](0008-ordering-and-concurrency.md) | Ordering by partition key, safety by optimistic locking | Accepted |
| [0009](0009-two-phase-withdrawals.md) | Two-phase withdrawals with holds, expiry and late confirmation | Superseded by 0015 |
| [0010](0010-interest-accrual-and-payout.md) | Daily ACT/365 accrual from end-of-day balances, monthly payout | Accepted; payout frequency amended by 0017 |
| [0011](0011-ledger-partitioning-and-keyset-pagination.md) | Monthly range partitions and keyset pagination for the ledger | Accepted |
| [0012](0012-acceptance-tests-first.md) | Acceptance tests first: Karate + Testcontainers | Accepted |
| [0013](0013-observability.md) | Business metrics, dashboard and alerts | Accepted |
| [0014](0014-double-entry-postings-for-finance.md) | Savings publishes balanced double-entry postings for Finance | Accepted |
| [0015](0015-transfers-only-with-the-current-account.md) | Savings moves money only to and from the customer's current account | Accepted |
| [0016](0016-domain-model-separate-from-jpa-entities.md) | Domain model separate from JPA entities, boundaries checked on every build | Accepted |
| [0017](0017-savings-products.md) | Savings products: versioned terms from a catalogue | Accepted |
| [0018](0018-service-structure.md) | Service structure: savings, transfers, current account | Accepted |
| [0019](0019-state-changes-through-a-command-log.md) | State changes only through a command log; REST for queries | Accepted |
| [0020](0020-open-and-fund-a-savings-product.md) | Opening a savings product: one order that opens the account, then asks for the money | Accepted |
| [0021](0021-current-account-service.md) | The current account service: overdraft, blocking, incoming payments | Accepted |
| [0022](0022-shared-technical-library.md) | A shared technical library for messaging; domain types stay per service | Accepted |
| [0023](0023-watching-transfers-in-flight.md) | Watching transfers in flight: stuck transfers, late requests, account conflicts | Accepted |
