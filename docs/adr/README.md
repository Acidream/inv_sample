# Architecture Decision Records

Each record captures one decision: the forces at play, what was chosen, and what it costs.
Format: [Michael Nygard](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

| # | Decision | Status |
|---|---|---|
| [0001](0001-scope-savings-only.md) | Scope: savings accounts only, conscious omissions | Accepted |
| [0002](0002-one-service-and-a-contract-library.md) | One service plus a shared Protobuf contract library | Accepted |
| [0003](0003-hexagonal-layers-with-jpa-on-the-domain.md) | Lightweight hexagonal layers, JPA annotations allowed on the domain | Accepted |
| [0004](0004-money-and-append-only-ledger.md) | Money as BigDecimal, append-only ledger with cached balance | Accepted |
| [0005](0005-protobuf-event-contracts.md) | Kafka contracts: Protobuf envelope with `oneof`, keyed by account | Accepted |
| [0006](0006-transactional-outbox.md) | Publish events through a transactional outbox | Accepted |
| [0007](0007-idempotent-consumer-and-dead-letters.md) | Idempotent consumer, retries, dead-letter topic | Accepted |
| [0008](0008-ordering-and-concurrency.md) | Ordering by partition key, safety by optimistic locking | Accepted |
| [0009](0009-two-phase-withdrawals.md) | Two-phase withdrawals with holds, expiry and late confirmation | Superseded by 0015 |
| [0010](0010-interest-accrual-and-payout.md) | Daily ACT/365 accrual from end-of-day balances, monthly payout | Accepted |
| [0011](0011-ledger-partitioning-and-keyset-pagination.md) | Monthly range partitions and keyset pagination for the ledger | Accepted |
| [0012](0012-acceptance-tests-first.md) | Acceptance tests first: Karate + Testcontainers | Accepted |
| [0013](0013-observability.md) | Business metrics, dashboard and alerts | Accepted |
| [0014](0014-double-entry-postings-for-finance.md) | Savings publishes balanced double-entry postings for Finance | Accepted |
| [0015](0015-transfers-only-with-the-current-account.md) | Savings moves money only to and from the customer's current account | Accepted |
