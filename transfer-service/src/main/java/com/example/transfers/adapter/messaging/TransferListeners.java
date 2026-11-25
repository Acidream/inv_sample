package com.example.transfers.adapter.messaging;

import static com.example.transfers.adapter.messaging.WireFormat.detail;
import static com.example.transfers.adapter.messaging.WireFormat.messageId;
import static com.example.transfers.adapter.messaging.WireFormat.transferId;
import static com.example.transfers.adapter.messaging.WireFormat.uuid;

import com.example.events.currentaccount.v1.CurrentAccountEvent;
import com.example.events.investments.v1.PortfolioEvent;
import com.example.events.payments.v1.TransferRequest;
import com.example.events.savings.v1.AccountEvent;
import com.example.transfers.application.LedgerOutcome;
import com.example.transfers.application.LedgerOutcome.Kind;
import com.example.transfers.application.TransferService;
import com.example.transfers.domain.KnownAccount;
import com.example.transfers.domain.KnownAccount.Ledger;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Everything the transfer service consumes: its own request log, and the events of the three ledgers.
 * Ledger events become account directory entries and saga outcomes; the rest is ignored. Offsets are
 * committed after the transaction, so a crash causes a redelivery, recognised by message id.
 *
 * <p>An event without a payload this service knows is skipped, not dead-lettered: a ledger adding an
 * event type is an additive contract change (ADR 0005), and this service, compiled against the older
 * contract, sees the new case as an unset payload.
 */
@Component
class TransferListeners {

    private static final Logger log = LoggerFactory.getLogger(TransferListeners.class);

    private final TransferService transfers;

    TransferListeners(TransferService transfers) {
        this.transfers = transfers;
    }

    @KafkaListener(topics = "${transfers.topics.transfer-requests}")
    void onRequest(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var message = TransferRequest.parseFrom(record.value());
        transfers.request(messageId(message.getRequestId()), WireFormat.request(message));
    }

    @KafkaListener(topics = "${transfers.topics.savings-events}")
    void onSavingsEvent(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var event = AccountEvent.parseFrom(record.value());
        var id = "savings:" + messageId(event.getEventId());
        var from = new Source(id, Ledger.SAVINGS, event.getAccountId(), "account_id");
        switch (event.getPayloadCase()) {
            case ACCOUNT_OPENED -> transfers.accountOpened(id, new KnownAccount(
                    uuid(event.getAccountId(), "account_id"),
                    uuid(event.getAccountOpened().getCustomerId(), "customer_id"), Ledger.SAVINGS));
            case MONEY_DEPOSITED -> outcome(from, event.getMoneyDeposited().getTransferId(),
                    Kind.CREDITED, "");
            case MONEY_WITHDRAWN -> outcome(from, event.getMoneyWithdrawn().getTransferId(),
                    Kind.DEBITED, "");
            case WITHDRAWAL_REJECTED -> outcome(from, event.getWithdrawalRejected().getTransferId(),
                    Kind.DEBIT_REJECTED,
                    event.getWithdrawalRejected().getReason().name());
            case WITHDRAWAL_RETURNED -> outcome(from, event.getWithdrawalReturned().getTransferId(), Kind.RETURNED, "");
            default -> log.debug("Ignoring savings event {} ({}): not a transfer's business", id,
                    event.getPayloadCase());
        }
    }

    @KafkaListener(topics = "${transfers.topics.current-account-events}")
    void onCurrentAccountEvent(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var event = CurrentAccountEvent.parseFrom(record.value());
        var id = "current-account:" + messageId(event.getEventId());
        var from = new Source(id, Ledger.CURRENT_ACCOUNT, event.getAccountId(), "account_id");
        switch (event.getPayloadCase()) {
            case CURRENT_ACCOUNT_OPENED -> transfers.accountOpened(id, new KnownAccount(
                    uuid(event.getAccountId(), "account_id"),
                    uuid(event.getCurrentAccountOpened().getCustomerId(), "customer_id"), Ledger.CURRENT_ACCOUNT));
            case CURRENT_ACCOUNT_DEBITED -> outcome(from, event.getCurrentAccountDebited().getTransferId(),
                    Kind.DEBITED, "");
            case DEBIT_REJECTED -> outcome(from, event.getDebitRejected().getTransferId(),
                    Kind.DEBIT_REJECTED, event.getDebitRejected().getReason());
            case CURRENT_ACCOUNT_CREDITED -> outcome(from, event.getCurrentAccountCredited().getTransferId(),
                    Kind.CREDITED, "");
            case CREDIT_REJECTED -> outcome(from, event.getCreditRejected().getTransferId(),
                    Kind.CREDIT_REFUSED, event.getCreditRejected().getReason());
            default -> log.debug("Ignoring current account event {} ({}): not a transfer's business", id,
                    event.getPayloadCase());
        }
    }

    @KafkaListener(topics = "${transfers.topics.investment-events}")
    void onInvestmentEvent(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var event = PortfolioEvent.parseFrom(record.value());
        var id = "investments:" + messageId(event.getEventId());
        var portfolio = event.getPortfolioId();
        var from = new Source(id, Ledger.INVESTMENTS, portfolio, "portfolio_id");
        switch (event.getPayloadCase()) {
            case PORTFOLIO_OPENED -> transfers.accountOpened(id, new KnownAccount(
                    uuid(portfolio, "portfolio_id"),
                    uuid(event.getPortfolioOpened().getCustomerId(), "customer_id"), Ledger.INVESTMENTS));
            case MONEY_DEPOSITED -> outcome(from, event.getMoneyDeposited().getTransferId(),
                    Kind.CREDITED, "");
            case MONEY_WITHDRAWN -> outcome(from, event.getMoneyWithdrawn().getTransferId(),
                    Kind.DEBITED, "");
            case WITHDRAWAL_REJECTED -> outcome(from, event.getWithdrawalRejected().getTransferId(),
                    Kind.DEBIT_REJECTED, event.getWithdrawalRejected().getReason());
            case WITHDRAWAL_RETURNED -> outcome(from, event.getWithdrawalReturned().getTransferId(),
                    Kind.RETURNED, "");
            default -> log.debug("Ignoring investments event {} ({}): not a transfer's business", id,
                    event.getPayloadCase());
        }
    }

    /** The ledger a message came from, and the account it is about. */
    private record Source(String messageId, Ledger ledger, String accountId, String accountField) {
    }

    private void outcome(Source from, String transferId, Kind kind, String detail) {
        transfers.outcome(new LedgerOutcome(from.messageId(), from.ledger(),
                uuid(from.accountId(), from.accountField()), transferId(transferId), kind, detail(detail)));
    }
}
