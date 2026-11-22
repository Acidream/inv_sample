# 0013 – Business metrics, dashboard and alerts

Date: 2026-11-22 · Status: Accepted

## Context

JVM and HTTP metrics show that the service runs, not that money flows correctly. The failure modes
that matter here are business-level: events stuck in the outbox, the consumer falling behind,
instructions that can't be applied.

## Decision

- Micrometer with the Prometheus registry, exposed at `/actuator/prometheus`, tagged
  `application=savings-service`.
- Business metrics behind a port (`SavingsMetrics`), so use cases don't depend on Micrometer:

| Metric | Type | Meaning |
|---|---|---|
| `savings_deposits_total`, `savings_deposits_amount_euros_total` | counter | Deposits and volume |
| `savings_withdrawals_total{outcome}` | counter | withdrawn / rejected / returned |
| `savings_interest_paid_euros_total` | counter | Interest credited |
| `savings_messages_duplicate_total` | counter | Redeliveries and re-sent transfers skipped by the idempotent consumer |
| `savings_messages_dead_lettered_total{topic}` | counter | Instructions parked on the dead-letter topic |
| `savings_outbox_pending` | gauge | Events committed but not yet in Kafka |
| `savings_outbox_published_total` | counter | Relay throughput |
| `kafka_consumer_fetch_manager_records_lag_max` | gauge | Consumer lag (Kafka client metric) |

- docker-compose runs Prometheus with alert rules (`ops/prometheus/alerts.yml`) and Grafana with a
  provisioned dashboard (`ops/grafana/dashboards/savings.json`).
- Alerts: **SavingsInstructionDeadLettered** (page),
  **SavingsOutboxBacklog** (page), **SavingsConsumerLag** (warn).
- Counters behind an `increase()` alert are registered at start-up with value 0. Micrometer creates
  a counter on first use, and Prometheus can't compute an increase for a series that jumps from
  absent to 1, so the first dead letter would never alert.
- Metric amounts are doubles. Fine for dashboards, never used for bookkeeping.

## Consequences

- Prometheus 3 negotiates a text format the actuator rejects (HTTP 406); the scrape config pins
  `scrape_protocols`.
- Next steps: tracing (OpenTelemetry, with the `event_id` as span attribute across Kafka hops),
  Alertmanager routing, and alerts on reduced redundancy (broker replicas, database failover quorum),
  not only on failure.
