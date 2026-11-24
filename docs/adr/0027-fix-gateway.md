# 0027 – The FIX gateway: one session to the broker, Kafka on the inside

Date: 2026-11-24 · Status: Accepted · For [0026](0026-investment-service.md)

## Context

The investment service places market orders with a broker (ADR 0026). The broker speaks FIX, as
brokers and exchanges do: a stateful session over one TCP connection, with sequence numbers on both
sides, resend requests for gaps, heartbeats and logons. Inside the platform everything is Kafka with
an outbox and idempotent consumers (ADR 0006, 0007).

Two questions: where the FIX session lives, and how an order reaches the broker exactly once and
every report comes back exactly once when either side may crash or disconnect at any moment.

## Decision

**A separate service, `fix-gateway` (port 8084), translates between Kafka and FIX 4.4.** The
investment service speaks only Kafka; nothing outside the gateway knows FIX (Checkstyle and ArchUnit
keep QuickFIX/J inside `adapter.fix`). The contract, proto package `broker.v1`, has no FIX terms in
it, so a second broker or protocol is another gateway, not another contract.

| Topic | Direction | Carries |
|---|---|---|
| `broker.orders.v1` | investments → gateway (its command log) | `PlaceOrder { order_id, isin, side, cash_amount \| quantity, trading_day }` |
| `broker.executions.v1` | gateway → investments | `OrderAccepted`, `OrderFilled { exec_id, quantity, price, done }`, `OrderRejected`, `OrderExpired` |

**FIX 4.4 with QuickFIX/J; the gateway initiates.** An order is a `NewOrderSingle`: `ClOrdID` = the
order id, `OrdType` market, `TimeInForce` day, the instrument by `SecurityIDSource=4` (ISIN) and
`SecurityID` (and in `Symbol`, which the dictionary requires), `Currency=EUR`, the bank's omnibus
account in `Account` (tag 1). A buy names its money in `CashOrderQty`, a sell its units in `OrderQty`;
decimals go on the wire as exact strings, never doubles. One customer order is one FIX order; the
broker never learns who the customer is.

**Reports, as `ExecutionReport`s, become the contract's events:**

| ExecType | Published |
|---|---|
| New | `OrderAccepted` (once) |
| Trade (and the older Partial fill / Fill) | `OrderFilled` with `LastQty`, `LastPx`; `done` when `OrdStatus=Filled` |
| Rejected | `OrderRejected` with `Text`; not if `OrdRejReason=Duplicate order` while the gateway has the order with the broker |
| Expired | `OrderExpired` |
| Canceled | `OrderRejected` ("cancelled by the broker: …") |
| Order status | nothing; used for recovery, below |

**Exactly once, inside the gateway's own database:**

- *Orders in.* A `PlaceOrder` is stored once per order id (`broker_order`); a redelivered or re-sent
  one changes nothing, the same order id with other details is dead-lettered. The gateway doesn't
  rely on the broker refusing duplicate `ClOrdID`s.
- *Orders out.* A dispatcher sends stored orders while the session is logged on. Each order is marked
  `SENDING` and committed **before** it goes out, then `SENT`. A crash in between leaves it `SENDING`:
  the gateway doesn't know whether it left, so it asks instead of sending it again (recovery, below).
  So an order reaches the broker at most once, unless the broker loses it.
- *Reports in.* Each report is applied in a transaction before QuickFIX/J counts it as received;
  fills are de-duplicated by exec id (`broker_execution`), so resends and `PossDupFlag` copies reach
  investments once. Investments de-duplicates again by message id and exec id (ADR 0007). If applying
  a report keeps failing, the gateway drops the session, so the broker resends it after the next logon
  rather than it being lost.
- *Reports out.* Through the outbox (ADR 0006), keyed by order id, so investments sees an order's
  reports in the broker's order.
- *Session state.* QuickFIX/J's `JdbcStore` keeps sequence numbers and sent messages in the gateway's
  Postgres (`sessions`, `messages`), so a restart, or another instance taking over, continues the
  session where it was, and a message handed to the session is resent if the broker asks for it.

**Recovery after every logon.** The gateway sends an `OrderStatusRequest` for every order the broker
may have and hasn't ended. A `SENDING` order the broker doesn't know never left: it goes out again.
A `SENT` or accepted order the broker doesn't know was lost by the broker: investments gets
`OrderRejected` ("the broker does not know the order"), so the order's money is refunded rather than
left in flight. A known order is left alone; its reports follow.

**Orders wait in the gateway while the session is down**, not in Kafka: the consumer keeps storing
orders, the dispatcher sends nothing until logon. A market order is good for its trading day only (a
Berlin date, set by investments); one still unsent when its day ends expires without reaching the
broker, and investments refunds it.

**One instance holds the session.** Sequence numbers belong to one connection. Every instance tries a
Postgres advisory lock on a connection of its own; the one that gets it starts the session and keeps
it while that connection lives, and stops it if the connection is lost. Another instance then takes
over from the shared session state.

**Monitoring.** `fix.session.logged.on` (alert `FixSessionDown`: no logged-on session for five minutes
on a weekday), `fix.session.held`, and `gateway.orders.overdue`, orders the broker hasn't ended an hour
after their day (alert `InvestmentOrderOverdue`, with investments' own `investments.orders.overdue`).

**For local demos and tests**, `execution-simulator` plays the broker in `docker compose`: an
acceptor that fills every order at once at a fixed price per ISIN. The gateway's acceptance tests run
a scripted acceptor in the test JVM: fills, partial fills, rejections, expiry, cancellation, duplicate
and `PossDupFlag` reports, refused logons, a broker that lost an order.

## Consequences

- A broker outage delays orders and, at worst, expires them unsent; investments then refunds the
  money. Nothing is traded twice and nothing is lost.
- One more service and database to run, for one TCP connection. That is the price of keeping FIX and
  its session rules out of the investment service.
- Recovery relies on the broker answering status requests, with "unknown order" for orders it doesn't
  have, as FIX 4.4 brokers do. A status that says an order ended without the reports having arrived is
  not turned into reports: the session-level resend is what delivers them.
- Whether QuickFIX/J counts a message whose processing threw as received depends on its settings;
  dropping the session after repeated failures and asking for status after the next logon are the
  safety net.

## Alternatives considered

- **The FIX session inside the investment service** (an adapter, one leader instance). One service
  fewer; rejected so that the investment service stays pure Kafka and a second user of the broker
  connection doesn't need investments.
- **Pause the Kafka consumer while the session is down**, so orders wait in Kafka. Equivalent for
  ordering, but orders whose day ends would only expire once consumed again; waiting in the gateway's
  table lets them expire on time.
- **Send first, then mark sent**, relying on the broker to reject a duplicate `ClOrdID` after a crash.
  Works with brokers that do; marking before sending plus a status request works with any.
- **Bundle customer orders into block orders** and allocate fills. Fewer, larger orders, at the cost of
  an allocation step; recorded as a later optimisation (ADR 0026).
