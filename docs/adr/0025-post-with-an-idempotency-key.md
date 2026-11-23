# 0025 – REST creates with POST and an Idempotency-Key; ids derived from the key

Date: 2026-11-23 · Status: Accepted · Amends [0019](0019-state-changes-through-a-command-log.md), [0024](0024-outcomes-checked-against-own-records.md)

## Context

The REST intakes created orders, transfers, current accounts and incoming payments with
`PUT /<collection>/{id}`, the id chosen by the client. That made a retry safe and the command log
replayable, but `POST` to the collection is what most clients and API guidelines expect for
creating something. A `POST` with a server-generated random id would lose both properties: a client
whose response was lost couldn't tell whether a transfer exists, a retry could move money twice, and
a replay of the command log would create different ids.

## Decision

- **Create with `POST <collection>` and an `Idempotency-Key` header**, as many payment APIs do:
  `POST /orders`, `POST /transfers`, `POST /current-accounts`,
  `POST /current-accounts/{accountId}/incoming-payments`.
- **The key is required.** A `POST` without one is refused with `400`, so no request can create
  something that can't be retried safely. A key is 1 to 100 printable ASCII characters without
  spaces; a UUID per business action is the recommended choice.
- **The resource id is derived from the key**, as a name-based UUID of `<scope>:<key>`:
  `orderId` and `accountId` are that UUID, a transfer id is `tr-<uuid>`, a payment id is
  `pay-<uuid>` with the key scoped to the account. The scope keeps one key from naming two kinds of
  resource. The derived id travels on the command log, so a replay doesn't depend on the derivation.
- Everything else stays as ADR 0019 describes: `202 Accepted` with the id in the body and in
  `Location`; the same key with the same body returns the resource as it is; the same key with a
  different body is `422`; two requests with the same key racing each other are applied once.
- **Blocking stays `PUT`/`DELETE /current-accounts/{id}/block`**: it sets and clears a state on an
  existing account and creates nothing.

## Consequences

- Clients no longer choose transfer ids, so they can't take one another service uses, such as
  savings' `savings-order-<orderId>`. ADR 0024's reserved prefix now holds by construction; savings
  still checks a funding report against its own deposit.
- An incoming payment's id is no longer the sender's reference (e.g. `salary-1`); the sender's
  reference is the key. Payment ids look like `pay-<uuid>`.
- The key is not stored: only the id derived from it is. Finding a resource by its key means
  deriving the id again; there is no lookup table and no expiry of keys.
- Keys are not scoped to a customer: there is no authentication yet (ADR 0001). With authentication,
  the scope should include the caller, so two callers can't collide on a key.
