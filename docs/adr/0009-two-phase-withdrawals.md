# 0009 – Two-phase withdrawals with holds, expiry and late confirmation

Date: 2026-11-22 · Status: Superseded by [0015](0015-transfers-only-with-the-current-account.md)

> Kept for the record: this was the design while Savings paid out to external accounts. Savings now
> only moves money to and from the customer's current account, which removes holds, expiry and late
> confirmations entirely (ADR 0015).

## Context

Savings owns the balance; Payments owns the outgoing SEPA transfer, which can fail after it was
requested. Debiting at request time would require compensating credits for every failed transfer,
and the customer would briefly see money vanish and reappear.

## Decision

```
Payments                         Savings
WithdrawalRequested(reqId, amt) ─▶ available ≥ amt ?
                                    yes: Hold RESERVED, held += amt ─▶ FundsReserved
                                    no:  ─▶ WithdrawalRejected(INSUFFICIENT_FUNDS)
(executes transfer)
WithdrawalConfirmed(reqId) ───────▶ Hold SETTLED, held −= amt, ledger −amt ─▶ MoneyWithdrawn
WithdrawalCancelled(reqId) ───────▶ Hold RELEASED, held −= amt ─▶ FundsReleased(CANCELLED)
(no answer for 72 h)                Hold EXPIRED, held −= amt ─▶ FundsReleased(EXPIRED)
```

- `Hold` is a small state machine (`domain.Hold`). Repeating a transition that already happened is a
  no-op; contradicting one (confirm after cancel, cancel after settle) raises
  `IllegalHoldTransitionException` → dead-letter topic (ADR 0007).
- Held money still belongs to the customer and **still earns interest**: interest is based on the
  ledger balance, not the available balance (ADR 0010).
- **Expiry:** unanswered holds are released after 72 h by a ShedLock job, so funds can't stay blocked
  forever if Payments loses a message.
- **Late confirmation:** if `WithdrawalConfirmed` arrives after the hold expired, it is **still
  booked**. A confirmation is a fact ("the money has left the bank"), not a request that can be
  refused. If the funds were spent again meanwhile, the account goes negative; that is counted
  (`savings_negative_balance_total`), logged and raises the `SavingsAccountOverdrawn` alert so
  operations can recover the money from the customer.

## Alternatives considered

- **Debit at request, compensate on failure:** simpler state, but customer-visible flicker and more
  bookings to reconcile.
- **Never expire holds:** no negative balances, but a lost message blocks the customer's money
  indefinitely. Expiry plus an alert is the better trade.
- **Reject late confirmations:** would make the ledger disagree with reality.

## Consequences

- Negative balances are possible, by design, in one narrow, monitored case.
- The 72 h TTL must be longer than Payments' worst-case processing time; it is configuration
  (`savings.hold-ttl`) and part of the contract between the teams.
