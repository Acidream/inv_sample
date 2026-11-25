# Glossary

Terms as they are used in code, events and docs. If code and glossary disagree, one of them is a bug.

| Term | Meaning | In code |
|---|---|---|
| **Account** | A customer's EUR savings account, opened on one product version whose terms it keeps. The aggregate that guards every balance change. | `domain.Account` |
| **Product** | What a customer opens, e.g. `INSTANT_ACCESS`. Its terms live in versions; it can be taken off sale from a given time. | `savings_product`, `GET /products` |
| **Product version** | One immutable set of terms of a product: annual rate and payout frequency, valid from a point in time. The current version is the latest one whose `valid_from` has passed. | `domain.SavingsProduct`, `savings_product_version` |
| **Payout frequency** | How often a product credits interest: MONTHLY, QUARTERLY or YEARLY, in calendar periods. | `domain.PayoutFrequency` |
| **Balance** | Money on the account according to the ledger. Never negative: the account and the database refuse it. | `Account.balance()` |
| **Ledger** | Append-only list of bookings per account. Never updated or deleted; corrections are new entries. | `domain.LedgerEntry`, `ledger_entry` |
| **Ledger entry** | One booking: entry number, type (DEPOSIT, WITHDRAWAL, WITHDRAWAL_RETURN, INTEREST), signed amount, balance after, business reference, booking time. | `LedgerEntry` |
| **Entry number** | Numbers an account's ledger entries 1, 2, 3, ... in booking order. With the booking time, which never goes backwards on one account, it defines the order of the ledger. | `LedgerEntry.entryNo` |
| **Current account** | The customer's main account at the bank, owned by the Current Account team. The only place savings and portfolio money comes from or goes to. Its balance may go below zero down to the overdraft limit. | `current-account-service`, `domain.CurrentAccount` |
| **Overdraft limit** | How far a current account's balance may go below zero. A debit that would go further is rejected ("overdraft limit exceeded"). Set when the account is opened. | `CurrentAccount.overdraftLimit()` |
| **Available** | What a current account can still spend: balance plus the unused overdraft; zero while blocked. | `CurrentAccount.available()` |
| **Blocked** | A current account that refuses transfers both ways ("account blocked"); incoming payments are still booked. | `CurrentAccount.Status.BLOCKED` |
| **Incoming payment** | Money arriving at a current account from outside the bank, e.g. a salary, under a payment id the sender chose. Booked once. | `IncomingPayment`, `current_account.v1.ReceivePayment` |
| **Transfer answer** | The current account's answer to one instruction of the transfer service, carried out or rejected; kept so the same transfer is answered once. | `TransferAnswer`, `transfer_answer` |
| **Payments** | The team that orchestrates transfers between the current account and a product ledger (savings or a portfolio), and makes external payments from the current account. Owns the transfer service. | `transfer-service`, `payments.v1` |
| **Transfer service** | Moves money between two accounts of the same customer as a saga across their ledgers. Knows accounts, not products. | `transfer-service`, `Transfer` |
| **Transfer request** | Asks the transfer service to move an amount from one account to another, under a transfer id the requester chose. | `payments.v1.TransferRequest` |
| **Account directory** | The transfer service's list of known accounts (owner, ledger), built from `AccountOpened`, `CurrentAccountOpened` and `PortfolioOpened` events. | `known_account`, `AccountDirectory` |
| **Order** | A customer's request to open a savings account on a product version and fund it from their current account. `REJECTED`, `FUNDING`, `FUNDED` or `FUNDING_FAILED`. | `domain.order.Order`, `savings_order` |
| **Product ledger** | A ledger other than the current account's: savings or investments. Both take the same instructions from the transfer service, each on its own topic. | `KnownAccount.Ledger`, `Step.DebitProductLedger` |
| **Portfolio** | A customer's investments: holdings, and a money ledger that is empty at rest. Opened with a reference account, the current account that refunds and proceeds go to. A third ledger to the transfer service. | `investments.domain.Portfolio`, `portfolio` |
| **Instrument** | An ETF or stock a customer can buy, identified by its ISIN, traded in EUR. Can be taken off sale from a time: then sold, not bought. | `Instrument`, `instrument`, `GET /instruments` |
| **ISIN** | The 12-character international securities identification number, e.g. `IE00B4L5Y983`; how instruments are named everywhere, including on FIX (`SecurityIDSource=4`). | `Instrument.ISIN` |
| **Investment order** | A market order for one instrument: a buy names an amount of money, a sell a number of units. Its trading status (`FUNDING`, `PLACED`, `PARTIALLY_FILLED`, `FILLED`, `EXPIRED`, `REJECTED`, `FUNDING_FAILED`) and its settlement are kept apart. Id `ord-…`, derived from the idempotency key. | `investments.domain.Order`, `investment_order` |
| **Funding** | The transfer that brings a buy's money from the current account to the portfolio, `inv-buy-<order id>`, before the order goes to the broker. | `Order.fundingTransferId` |
| **Trading day** | The Berlin weekday an order trades on, the Monday after for an order placed on a weekend; a market order expires at its end. No exchange holiday calendar yet. | `TradingCalendar` |
| **Execution** | What the broker reported for one fill of an order: execution id, quantity, price, trade time. | `Execution` |
| **Broker report** | What the broker said about an order: accepted, filled, rejected or expired. The gateway reads it from FIX, de-duplicates it by execution id and publishes it; investments applies it to the order. | `broker.v1.BrokerExecution`, `fixgateway.application.BrokerReport`, `investments.application.BrokerReport` |
| **Fill** | An execution applied to its order. Its amount, the money it moves, is what the order's exact total (quantity × price summed) rounded half-even to the cent grew by; zero for a fill worth less than a cent. | `Fill`, `order_fill` |
| **Portfolio money ledger** | A portfolio's append-only money bookings (FUNDING, BUY, SELL, PAYOUT, PAYOUT_RETURN), empty at rest: money for orders and payouts in flight, and unpaid payouts. Not a cash account: nothing on it can be spent on a new order. | `CashEntry`, `cash_entry`, `Portfolio.cash()`, `Books.Ledger` |
| **Booking** | One entry about to go on a portfolio's money ledger; every booking is also a posting for Finance. | `CashEntry.Booking`, `Books` |
| **Quantity** | Units of an instrument, fractional, at most 6 fraction digits, never rounded by us. | `Quantity` |
| **Lot** | The units one buy fill added to a holding, with what is left of them and of their cost (10 fraction digits). Sold first in, first out. | `Lot`, `lot` |
| **Holding** | What a portfolio holds of one instrument: its lots, and the units open sells have reserved. | `Holding`, `holding` |
| **Free units** | A holding's units not reserved by open sells: the most a new sell may take. | `Holding.free()` |
| **Cost basis** | What the units held cost, by their lots. | `Holding.costBasis()` |
| **Realised gain** | A sell fill's proceeds minus the FIFO cost of the lots it consumed; may be negative. | `Holding.Sale`, `Fill.realizedGain` |
| **Payout** | Money a portfolio owes its reference account: a buy's refund, a sell's proceeds, or money that arrived with no order waiting. Each attempt is its own transfer, `inv-payout-<id>-<attempt>`. | `Payout`, `payout` |
| **Unpaid payout** | A payout the current account refused, e.g. while blocked: the money is back on the portfolio, retried when the account is unblocked and daily, never spent. | `Payout.Status.UNPAID` |
| **Settlement** | Whether an investment order's money is where it belongs: `PENDING`, `SETTLED`, or `UNPAID`. | `Order.Settlement` |
| **broker-settlement** | The book account for money owed to or by the broker for fills. Securities are not posted: held in custody, off the balance sheet. | `BookAccount.BROKER_SETTLEMENT` (investments) |
| **Broker** | The firm that executes investment orders on the market; outside the bank, reached over FIX. Played by `execution-simulator` locally. | `broker.v1` |
| **FIX gateway** | The service that holds the one FIX 4.4 session to the broker and translates between it and Kafka. | `fix-gateway`, `GatewayService` |
| **FIX session** | One connection to the broker with sequence numbers both ways; its state (sequence numbers, sent messages) is in the gateway's database. One instance holds it at a time. | `FixSession`, `sessions`, `messages` |
| **Omnibus account** | The bank's single account at the broker (FIX tag 1); customers are never named to the broker. | `gateway.fix.account` |
| **Command log** | A service's own input topic. Every state change starts as a message there; REST only appends to it. | `savings.commands.v1`, `payments.transfer-requests.v1`, `current-account.commands.v1` |
| **Payment instruction** | A message from Payments: TransferFromCurrentAccount, TransferToCurrentAccount or TransferReturned. | `payments.v1.PaymentInstruction`, savings' `application.PaymentInstruction`, investments' `application.Instruction` |
| **Transfer** | One movement between the current account and a product ledger (savings or a portfolio), identified by a `transferId` chosen by Payments. States: IN COMPLETED; OUT COMPLETED, REJECTED or RETURNED. | `domain.Transfer`, `transfer` |
| **Deposit** | Transfer in. The current account was already debited; Savings credits at once. | `Account.deposit` |
| **Withdrawal** | Transfer out. Debited at once if the balance allows it, otherwise rejected. Single-phase: no holds. | `Account.withdraw` |
| **Return** | Compensation: the current account refused a withdrawal, Savings credits it back. Once per transfer. | `Transfer.markReturned`, `Account.creditReturnedWithdrawal` |
| **Annual rate** | Interest rate per year as a decimal fraction (`0.0250` = 2.5 % p.a.), 4 fraction digits. Set by the product version. | `SavingsProduct.annualRate` |
| **Business day / zone** | Calendar days are counted in `Europe/Berlin`. "End of day" means midnight Berlin time. | `SavingsPolicy.businessZone` |
| **End-of-day balance** | Balance after the last ledger entry booked before midnight (Berlin) of that day. Interest is based on it. | `Ledger.balanceAt` |
| **Accrual** | Interest earned for one day, ACT/365: `end-of-day balance × rate ÷ 365`, 10 fraction digits, never negative. | `Interest.forOneDay`, `interest_accrual` |
| **Accrued interest** | Sum of accruals not yet paid out. Sub-cent precision. | `Account.accruedInterest` |
| **Payout** | Credit of accrued interest at the end of each payout period, rounded down to cents. The rounding remainder (under a cent) stays accrued. | `Account.payOutInterest` |
| **Period** | The payout period a payout belongs to: `2026-01` (month), `2026-Q1` (quarter) or `2026` (year), with its first and last day. | `domain.InterestPeriod`, `InterestPaid.period` |
| **Account event** | A fact Savings publishes for others: AccountOpened, MoneyDeposited, MoneyWithdrawn, WithdrawalRejected, WithdrawalReturned, InterestPaid. Every instruction gets exactly one. | `domain.AccountEvent`, `savings.v1.AccountEvent` |
| **Posting** | The bank-wide, double-entry view of a booking: lines whose debits equal their credits, published to Finance. Every ledger entry and every accrual has one; a rejected withdrawal doesn't. | `domain.Posting`, `savings.v1.Posting` |
| **Book account** | A logical account in the bank's books: `customer:<id>`, `transfer-clearing`, `interest-expense`, `interest-payable`, `broker-settlement`. Finance maps them to its chart of accounts. | `domain.BookAccount` (savings and investments, each its own) |
| **Value date** | The business day a posting belongs to in the books, which can differ from when it was computed (an accrual for 31 January booked after midnight). | `Posting.valueDate` |
| **Sub-ledger / general ledger** | Savings keeps the customer-level books (sub-ledger); Finance keeps the bank's books (general ledger, GL). Postings connect the two. | `Bookkeeping` |
| **Outbox** | Table where account events and postings are written in the business transaction before being relayed to Kafka. | `outbox_event`, `Outbox`, `OutboxRelay` |
| **Processed message** | Id of an incoming message that has already been applied; makes the consumer idempotent. Shared by all services. | `platform-messaging` `ProcessedMessages`, `processed_message` |
| **platform-messaging** | The technical library all services use: outbox and relay, idempotent consumer, retention, dead letters, wire checks. No domain rules. | `com.example.platform.messaging` |
| **Dead-letter topic (DLT)** | `<topic>.DLT`. Where messages go that can never be processed (malformed, contract violation). | `KafkaConfig` |
| **Contract violation** | An instruction that contradicts earlier ones, e.g. returning a rejected withdrawal or reusing a transfer id. Never silently ignored. | `IllegalTransferException`, `IllegalOrderTransitionException`, `IllegalTransferStateException`, `TransferIdInUseException` |
