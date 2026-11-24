package com.example.currentaccount.adapter.messaging;

import static com.example.currentaccount.adapter.messaging.WireFormat.money;
import static com.example.currentaccount.adapter.messaging.WireFormat.timestamp;

import com.example.currentaccount.application.port.LedgerEvents;
import com.example.currentaccount.config.CurrentAccountProperties;
import com.example.currentaccount.domain.CurrentAccount;
import com.example.currentaccount.domain.TransferAnswer;
import com.example.events.currentaccount.v1.CreditRejected;
import com.example.events.currentaccount.v1.CurrentAccountBlocked;
import com.example.events.currentaccount.v1.CurrentAccountCredited;
import com.example.events.currentaccount.v1.CurrentAccountDebited;
import com.example.events.currentaccount.v1.CurrentAccountEvent;
import com.example.events.currentaccount.v1.CurrentAccountOpened;
import com.example.events.currentaccount.v1.CurrentAccountUnblocked;
import com.example.events.currentaccount.v1.DebitRejected;
import com.example.platform.messaging.Outbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Events to {@code current-account.events.v1} through the shared {@link Outbox}, keyed by account id. */
@Component
class OutboxLedgerEvents implements LedgerEvents {

    private final Outbox outbox;
    private final String topic;

    OutboxLedgerEvents(Outbox outbox, CurrentAccountProperties properties) {
        this.outbox = outbox;
        this.topic = properties.topics().events();
    }

    @Override
    public void opened(CurrentAccount account) {
        publish(event(account.id(), account.openedAt()).setCurrentAccountOpened(CurrentAccountOpened.newBuilder()
                .setCustomerId(account.customerId().toString())));
    }

    @Override
    public void answered(TransferAnswer answer) {
        var event = event(answer.accountId(), answer.answeredAt());
        var transferId = answer.transferId();
        var debit = answer.direction() == TransferAnswer.Direction.DEBIT;
        if (debit && answer.carriedOut()) {
            event.setCurrentAccountDebited(CurrentAccountDebited.newBuilder()
                    .setTransferId(transferId).setAmount(money(answer.amount())));
        } else if (debit) {
            event.setDebitRejected(DebitRejected.newBuilder()
                    .setTransferId(transferId).setReason(answer.rejectionReason()));
        } else if (answer.carriedOut()) {
            event.setCurrentAccountCredited(CurrentAccountCredited.newBuilder()
                    .setTransferId(transferId).setAmount(money(answer.amount())));
        } else {
            event.setCreditRejected(CreditRejected.newBuilder()
                    .setTransferId(transferId).setReason(answer.rejectionReason()));
        }
        publish(event);
    }

    @Override
    public void blockChanged(CurrentAccount account, Instant at) {
        var event = event(account.id(), at);
        if (account.status() == CurrentAccount.Status.BLOCKED) {
            event.setCurrentAccountBlocked(CurrentAccountBlocked.newBuilder()
                    .setReason(account.blockReason().orElseThrow()));
        } else {
            event.setCurrentAccountUnblocked(CurrentAccountUnblocked.getDefaultInstance());
        }
        publish(event);
    }

    private static CurrentAccountEvent.Builder event(UUID accountId, Instant at) {
        return CurrentAccountEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(at))
                .setAccountId(accountId.toString());
    }

    private void publish(CurrentAccountEvent.Builder event) {
        var built = event.build();
        outbox.add(topic, built.getAccountId(), built.getPayloadCase().name(), built.toByteArray());
    }
}
