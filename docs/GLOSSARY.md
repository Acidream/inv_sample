# Glossary

Terms as they are used in code, events and docs. If code and glossary disagree, one of them is a bug.

| Term | Meaning | In code |
|---|---|---|
| **Account** | A customer's EUR savings account with a fixed variable rate. The aggregate that guards every balance change. | `domain.Account` |
| **Balance** | Money on the account according to the ledger. Never negative: the account and the database refuse it. | `Account.balance()` |
| **Ledger** | Append-only list of bookings per account. Never updated or deleted; corrections are new entries. | `domain.LedgerEntry`, `ledger_entry` |
| **Ledger entry** | One booking: entry number, type (DEPOSIT, WITHDRAWAL, WITHDRAWAL_RETURN, INTEREST), signed amount, balance after, business reference, booking time. | `LedgerEntry` |
| **Entry number** | Numbers an account's ledger entries 1, 2, 3, ... in booking order. With the booking time, which never goes backwards on one account, it defines the order of the ledger. | `LedgerEntry.entryNo` |
| **Current account** | The customer's main account at the bank, owned by another team. The only place savings money comes from or goes to. | not modelled |
| **Payments** | The team that orchestrates transfers between current account and savings, and makes external payments from the current account. | `payments.v1.PaymentInstruction` |
| **Payment instruction** | A message from Payments: TransferFromCurrentAccount, TransferToCurrentAccount or TransferReturned. | `payments.v1.PaymentInstruction`, `application.PaymentInstruction` |
| **Transfer** | One movement between current account and savings, identified by a `transferId` chosen by Payments. States: IN COMPLETED; OUT COMPLETED, REJECTED or RETURNED. | `domain.Transfer`, `transfer` |
| **Deposit** | Transfer in. The current account was already debited; Savings credits at once. | `Account.deposit` |
| **Withdrawal** | Transfer out. Debited at once if the balance allows it, otherwise rejected. Single-phase: no holds. | `Account.withdraw` |
| **Return** | Compensation: the current account refused a withdrawal, Savings credits it back. Once per transfer. | `Transfer.markReturned`, `Account.creditReturnedWithdrawal` |
| **Annual rate** | Interest rate per year as a decimal fraction (`0.0250` = 2.5 % p.a.), 4 fraction digits. | `Account.annualRate` |
| **Business day / zone** | Calendar days are counted in `Europe/Berlin`. "End of day" means midnight Berlin time. | `SavingsPolicy.businessZone` |
| **End-of-day balance** | Balance after the last ledger entry booked before midnight (Berlin) of that day. Interest is based on it. | `Ledger.balanceAt` |
| **Accrual** | Interest earned for one day, ACT/365: `end-of-day balance × rate ÷ 365`, 10 fraction digits, never negative. | `Interest.forOneDay`, `interest_accrual` |
| **Accrued interest** | Sum of accruals not yet paid out. Sub-cent precision. | `Account.accruedInterest` |
| **Payout** | Monthly credit of accrued interest, rounded half-even to cents. The rounding remainder stays accrued. | `Account.payOutInterest` |
| **Period** | The calendar month (`YYYY-MM`) a payout belongs to. | `InterestPaid.period` |
| **Account event** | A fact Savings publishes for others: AccountOpened, MoneyDeposited, MoneyWithdrawn, WithdrawalRejected, WithdrawalReturned, InterestPaid. Every instruction gets exactly one. | `domain.AccountEvent`, `savings.v1.AccountEvent` |
| **Posting** | The bank-wide, double-entry view of a booking: lines whose debits equal their credits, published to Finance. Every ledger entry and every accrual has one; a rejected withdrawal doesn't. | `domain.Posting`, `savings.v1.Posting` |
| **Book account** | A logical account in the bank's books: `customer:<id>`, `transfer-clearing`, `interest-expense`, `interest-payable`. Finance maps them to its chart of accounts. | `domain.BookAccount` |
| **Value date** | The business day a posting belongs to in the books, which can differ from when it was computed (an accrual for 31 January booked after midnight). | `Posting.valueDate` |
| **Sub-ledger / general ledger** | Savings keeps the customer-level books (sub-ledger); Finance keeps the bank's books (general ledger, GL). Postings connect the two. | `Bookkeeping` |
| **Outbox** | Table where account events and postings are written in the business transaction before being relayed to Kafka. | `outbox_event`, `Outbox`, `OutboxRelay` |
| **Processed event** | Id of an incoming message that has already been applied; makes the consumer idempotent. | `processed_event` |
| **Dead-letter topic (DLT)** | `<topic>.DLT`. Where messages go that can never be processed (malformed, contract violation). | `KafkaConfig` |
| **Contract violation** | An instruction that contradicts earlier ones, e.g. returning a rejected withdrawal or reusing a transfer id. Never silently ignored. | `IllegalTransferException`, `IllegalArgumentException` |
