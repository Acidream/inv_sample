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
            case Step.DebitSavings debit -> savings(debit.accountId().toString(), savingsInstruction(debit.accountId())
                    .setTransferToCurrentAccount(TransferToCurrentAccount.newBuilder()
                            .setTransferId(debit.transferId())
                            .setAmount(money(debit.amount()))));
            case Step.CreditSavings credit -> savings(credit.accountId().toString(),
                    savingsInstruction(credit.accountId()).setTransferFromCurrentAccount(
                            TransferFromCurrentAccount.newBuilder()
                                    .setTransferId(credit.transferId())
                                    .setAmount(money(credit.amount()))));
            case Step.ReturnToSavings returned -> savings(returned.accountId().toString(),
                    savingsInstruction(returned.accountId()).setTransferReturned(TransferReturned.newBuilder()
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

    private PaymentInstruction.Builder savingsInstruction(UUID accountId) {
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

    private void savings(String key, PaymentInstruction.Builder message) {
        var built = message.build();
        outbox.add(topics.savingsInstructions(), key, built.getPayloadCase().name(), built.toByteArray());
    }

    private void event(TransferEvent.Builder message) {
        var built = message.build();
        outbox.add(topics.transferEvents(), built.getTransferId(), built.getPayloadCase().name(), built.toByteArray());
    }
}
