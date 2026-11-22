# Glossary

Terms as they are used in code, events and docs. If code and glossary disagree, one of them is a bug.

| Term | Meaning | In code |
|---|---|---|
| **Account** | A customer's EUR savings account with a fixed variable rate. The aggregate that guards every balance change. | `domain.Account` |
| **Balance** | Money on the account according to the ledger. Can be negative only through a late confirmation. | `Account.balance()` |
| **Held amount** | Sum of all *reserved* holds: money still on the account but promised to an outgoing transfer. | `Account.heldAmount()` |
| **Available balance** | `balance − held amount`. The only figure a withdrawal is checked against. | `Account.availableBalance()` |
| **Ledger** | Append-only list of bookings per account. Never updated or deleted; corrections are new entries. | `domain.LedgerEntry`, `ledger_entry` |
| **Ledger entry** | One booking: type, signed amount, balance after, business reference, booking time. | `LedgerEntry` |
| **Payment instruction** | A message from the Payments team telling Savings that money arrived or should leave. | `payments.v1.PaymentInstruction`, `application.PaymentInstruction` |
| **Deposit** | Money received for the account (instruction `PaymentReceived`). Credited immediately. | `Account.deposit` |
| **Withdrawal** | Money leaving the account, always in two phases: *reserve*, then *confirm* or *cancel*. | `PaymentInstructionHandler` |
| **Withdrawal request** | Phase 1. Identified by a `requestId` chosen by Payments. Answered with *FundsReserved* or *WithdrawalRejected*. | `WithdrawalRequested` |
| **Hold** | The earmark created by a successful withdrawal request. States: RESERVED, SETTLED, RELEASED, EXPIRED. | `domain.Hold` |
| **Settle** | Phase 2a. Payments executed the transfer; the hold becomes a ledger debit. | `Hold.confirm`, `Account.settleHold` |
| **Release** | Phase 2b or expiry. The hold ends without a debit; funds are available again. | `Hold.cancel`, `Hold.expire` |
| **Hold TTL** | How long a hold waits for phase 2 before it expires (72 h). | `savings.hold-ttl` |
| **Late confirmation** | A confirmation for a hold that already expired. Booked anyway, because the money has already left the bank. May overdraw the account. | `Hold.Outcome.APPLIED_AFTER_EXPIRY` |
| **Annual rate** | Interest rate per year as a decimal fraction (`0.0250` = 2.5 % p.a.), 4 fraction digits. | `Account.annualRate` |
| **Business day / zone** | Calendar days are counted in `Europe/Berlin`. "End of day" means midnight Berlin time. | `SavingsPolicy.businessZone` |
| **End-of-day balance** | Balance after the last ledger entry booked before midnight (Berlin) of that day. Interest is based on it. | `Ledger.balanceAt` |
| **Accrual** | Interest earned for one day, ACT/365: `end-of-day balance × rate ÷ 365`, 10 fraction digits, never negative. | `Interest.forOneDay`, `interest_accrual` |
| **Accrued interest** | Sum of accruals not yet paid out. Sub-cent precision. | `Account.accruedInterest` |
| **Payout** | Monthly credit of accrued interest, rounded half-even to cents. The rounding remainder stays accrued. | `Account.payOutInterest` |
| **Period** | The calendar month (`YYYY-MM`) a payout belongs to. | `InterestPaid.period` |
| **Account event** | A fact Savings publishes for others: AccountOpened, MoneyDeposited, FundsReserved, WithdrawalRejected, MoneyWithdrawn, FundsReleased, InterestPaid. | `domain.AccountEvent`, `savings.v1.AccountEvent` |
| **Outbox** | Table where account events are written in the business transaction before being relayed to Kafka. | `outbox_event`, `OutboxRelay` |
| **Processed event** | Id of an incoming message that has already been applied; makes the consumer idempotent. | `processed_event` |
| **Dead-letter topic (DLT)** | `<topic>.DLT`. Where messages go that can never be processed (malformed, contract violation). | `KafkaConfig` |
| **Contract violation** | An instruction that contradicts earlier ones, e.g. confirming a cancelled withdrawal. Never silently ignored. | `IllegalHoldTransitionException` |
