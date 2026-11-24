package com.example.transfers.adapter.messaging;

import static com.example.transfers.adapter.messaging.WireFormat.money;
import static com.example.transfers.adapter.messaging.WireFormat.timestamp;

import com.example.events.payments.v1.CreditCurrentAccount;
import com.example.events.payments.v1.CurrentAccountInstruction;
import com.example.events.payments.v1.DebitCurrentAccount;
import com.example.events.payments.v1.PaymentInstruction;
import com.example.events.payments.v1.TransferCompleted;
import com.example.events.payments.v1.TransferEvent;
import com.example.events.payments.v1.TransferFailed;
import com.example.events.payments.v1.TransferFromCurrentAccount;
import com.example.events.payments.v1.TransferReturned;
import com.example.events.payments.v1.TransferToCurrentAccount;
import com.example.platform.messaging.Outbox;
import com.example.transfers.application.port.SagaMessages;
import com.example.transfers.config.TransferProperties;
import com.example.transfers.domain.KnownAccount.Ledger;
import com.example.transfers.domain.Step;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Saga steps to the wire, through the {@link Outbox}. Instructions are keyed by the account they are
 * for, so each ledger gets them in order per account; transfer events are keyed by transfer id.
 */
@Component
class OutboxSagaMessages implements SagaMessages {

    private final Outbox outbox;
    private final Clock clock;
    private final TransferProperties.Topics topics;

    OutboxSagaMessages(Outbox outbox, Clock clock, TransferProperties properties) {
        this.outbox = outbox;
        this.clock = clock;
        this.topics = properties.topics();
    }

    @Override
    public void send(Step step) {
        switch (step) {
            case Step.DebitCurrentAccount debit -> currentAccount(debit.accountId().toString(),
                    instruction(debit.accountId()).setDebitCurrentAccount(DebitCurrentAccount.newBuilder()
                            .setTransferId(debit.transferId())
                            .setAmount(money(debit.amount()))));
            case Step.CreditCurrentAccount credit -> currentAccount(credit.accountId().toString(),
                    instruction(credit.accountId()).setCreditCurrentAccount(CreditCurrentAccount.newBuilder()
                            .setTransferId(credit.transferId())
                            .setAmount(money(credit.amount()))));
            case Step.DebitProductLedger debit -> productLedger(debit.ledger(), productInstruction(debit.accountId())
                    .setTransferToCurrentAccount(TransferToCurrentAccount.newBuilder()
                            .setTransferId(debit.transferId())
                            .setAmount(money(debit.amount()))));
            case Step.CreditProductLedger credit -> productLedger(credit.ledger(),
                    productInstruction(credit.accountId()).setTransferFromCurrentAccount(
                            TransferFromCurrentAccount.newBuilder()
                                    .setTransferId(credit.transferId())
                                    .setAmount(money(credit.amount()))));
            case Step.ReturnToProductLedger returned -> productLedger(returned.ledger(),
                    productInstruction(returned.accountId()).setTransferReturned(TransferReturned.newBuilder()
                            .setTransferId(returned.transferId())));
            case Step.Completed completed -> event(event(completed.transferId())
                    .setTransferCompleted(TransferCompleted.getDefaultInstance()));
            case Step.Failed failed -> event(event(failed.transferId())
                    .setTransferFailed(TransferFailed.newBuilder()
                            .setReason(TransferFailed.Reason.valueOf(failed.reason().name()))
                            .setDetail(failed.detail() == null ? "" : failed.detail())));
            case Step.Wait wait -> throw new IllegalArgumentException("Nothing to send while waiting");
        }
    }

    private CurrentAccountInstruction.Builder instruction(UUID accountId) {
        return CurrentAccountInstruction.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(clock.instant()))
                .setAccountId(accountId.toString());
    }

    private PaymentInstruction.Builder productInstruction(UUID accountId) {
        return PaymentInstruction.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(clock.instant()))
                .setAccountId(accountId.toString());
    }

    private TransferEvent.Builder event(String transferId) {
        return TransferEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(clock.instant()))
                .setTransferId(transferId);
    }

    private void currentAccount(String key, CurrentAccountInstruction.Builder message) {
        var built = message.build();
        outbox.add(topics.currentAccountInstructions(), key, built.getPayloadCase().name(), built.toByteArray());
    }

    /** Savings and investments take the same instructions, each on its own topic (ADR 0028). */
    private void productLedger(Ledger ledger, PaymentInstruction.Builder message) {
        var topic = switch (ledger) {
            case SAVINGS -> topics.savingsInstructions();
            case INVESTMENTS -> topics.investmentInstructions();
            case CURRENT_ACCOUNT -> throw new IllegalArgumentException("The current account has its own instructions");
        };
        var built = message.build();
        outbox.add(topic, built.getAccountId(), built.getPayloadCase().name(), built.toByteArray());
    }

    private void event(TransferEvent.Builder message) {
        var built = message.build();
        outbox.add(topics.transferEvents(), built.getTransferId(), built.getPayloadCase().name(), built.toByteArray());
    }
}
