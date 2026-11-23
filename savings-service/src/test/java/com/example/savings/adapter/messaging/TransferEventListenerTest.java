package com.example.savings.adapter.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.events.payments.v1.TransferEvent;
import com.example.savings.application.order.OrderService;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * A transfer event of a type savings doesn't know is skipped: Payments adding an event type is an
 * additive contract change, and savings sees the new oneof case as an unset payload.
 */
class TransferEventListenerTest {

    private static final int FIELD_FROM_A_NEWER_CONTRACT = 99;

    private final OrderService orders = mock(OrderService.class);
    private final TransferEventListener listener = new TransferEventListener(orders);

    @Test
    void aTransferEventOfANewerTypeIsSkipped() throws Exception {
        var event = TransferEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTransferId("savings-order-" + UUID.randomUUID())
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(FIELD_FROM_A_NEWER_CONTRACT, UnknownFieldSet.Field.newBuilder()
                                .addLengthDelimited(ByteString.copyFromUtf8("new event")).build())
                        .build())
                .build();

        listener.onMessage(new ConsumerRecord<>("payments.transfer-events.v1", 0, 0, "key", event.toByteArray()));

        verifyNoInteractions(orders);
    }
}
