# 0013 – Business metrics, dashboard and alerts

Date: 2026-11-22 · Status: Accepted

## Context

JVM and HTTP metrics show that the service runs, not that money flows correctly. The failure modes
that matter here are business-level: events stuck in the outbox, the consumer falling behind,
overdrawn accounts.

## Decision

- Micrometer with the Prometheus registry, exposed at `/actuator/prometheus`, tagged
  `application=savings-service`.
- Business metrics behind a port (`SavingsMetrics`), so use cases don't depend on Micrometer:

| Metric | Type | Meaning |
|---|---|---|
| `savings_deposits_total`, `savings_deposits_amount_euros_total` | counter | Deposits and volume |
| `savings_withdrawals_total{outcome}` | counter | reserved / rejected / settled / released / expired |
| `savings_interest_paid_euros_total` | counter | Interest credited |
| `savings_negative_balance_total` | counter | Late confirmations that overdrew an account |
| `savings_messages_duplicate_total` | counter | Redeliveries skipped by the idempotent consumer |
| `savings_outbox_pending` | gauge | Events committed but not yet in Kafka |
| `savings_outbox_published_total` | counter | Relay throughput |
| `kafka_consumer_fetch_manager_records_lag_max` | gauge | Consumer lag (Kafka client metric) |

- docker-compose runs Prometheus with alert rules (`ops/prometheus/alerts.yml`) and Grafana with a
  provisioned dashboard (`ops/grafana/dashboards/savings.json`).
- Alerts: **SavingsAccountOverdrawn** (page), **SavingsOutboxBacklog** (page),
  **SavingsConsumerLag** (warn).
- Metric amounts are doubles. Fine for dashboards, never used for bookkeeping.

## Consequences

- Prometheus 3 negotiates a text format the actuator rejects (HTTP 406); the scrape config pins
  `scrape_protocols`.
- Next steps: tracing (OpenTelemetry, with the `event_id` as span attribute across Kafka hops),
  a DLT-traffic alert, and Alertmanager routing.
