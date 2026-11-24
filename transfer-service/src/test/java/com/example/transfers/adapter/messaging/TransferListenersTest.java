package com.example.transfers.adapter.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.events.currentaccount.v1.CurrentAccountEvent;
import com.example.events.investments.v1.PortfolioEvent;
import com.example.events.savings.v1.AccountEvent;
import com.example.transfers.application.TransferService;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * Ledger events of a type this service doesn't know are skipped: a ledger adding an event type is an
 * additive contract change, and an older consumer sees the new oneof case as an unset payload.
 */
class TransferListenersTest {

    private static final int FIELD_FROM_A_NEWER_CONTRACT = 99;

    private final TransferService transfers = mock(TransferService.class);
    private final TransferListeners listeners = new TransferListeners(transfers);

    @Test
    void onCurrentAccountEvent_EventOfNewerType_IsSkipped() throws Exception {
        var event = CurrentAccountEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setAccountId(UUID.randomUUID().toString())
                .setUnknownFields(newerPayload())
                .build();

        listeners.onCurrentAccountEvent(record("current-account.events.v1", event.toByteArray()));

        verifyNoInteractions(transfers);
    }

    @Test
    void onSavingsEvent_EventOfNewerType_IsSkipped() throws Exception {
        var event = AccountEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setAccountId(UUID.randomUUID().toString())
                .setUnknownFields(newerPayload())
                .build();

        listeners.onSavingsEvent(record("savings.account-events.v1", event.toByteArray()));

        verifyNoInteractions(transfers);
    }

    @Test
    void onInvestmentEvent_EventOfNewerType_IsSkipped() throws Exception {
        var event = PortfolioEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setPortfolioId(UUID.randomUUID().toString())
                .setUnknownFields(newerPayload())
                .build();

        listeners.onInvestmentEvent(record("investments.account-events.v1", event.toByteArray()));

        verifyNoInteractions(transfers);
    }

    private static UnknownFieldSet newerPayload() {
        return UnknownFieldSet.newBuilder()
                .addField(FIELD_FROM_A_NEWER_CONTRACT, UnknownFieldSet.Field.newBuilder()
                        .addLengthDelimited(ByteString.copyFromUtf8("new event")).build())
                .build();
    }

    private static ConsumerRecord<String, byte[]> record(String topic, byte[] value) {
        return new ConsumerRecord<>(topic, 0, 0, "key", value);
    }
}
