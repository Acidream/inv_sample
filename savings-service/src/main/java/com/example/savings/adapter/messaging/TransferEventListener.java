package com.example.savings.adapter.messaging;

import static com.example.savings.adapter.messaging.PaymentInstructionTranslator.id;

import com.example.events.payments.v1.TransferEvent;
import com.example.savings.application.order.OrderService;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Outcomes of transfers; savings cares about the ones that fund its orders (ADR 0020). */
@Component
class TransferEventListener {

    /** Stored as {@code "transfer-event:" + id} in {@code processed_event.event_id varchar(100)}. */
    static final int MAX_EVENT_ID_LENGTH = 80;
    /** {@code savings_order.failure_reason varchar(300)}. */
    static final int MAX_REASON_LENGTH = 300;

    private final OrderService orders;

    TransferEventListener(OrderService orders) {
        this.orders = orders;
    }

    @KafkaListener(topics = "${savings.topics.transfer-events}")
    void onMessage(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var event = TransferEvent.parseFrom(record.value());
        var eventId = id(event.getEventId(), "event_id", MAX_EVENT_ID_LENGTH);
        var transferId = id(event.getTransferId(), "transfer_id");
        switch (event.getPayloadCase()) {
            case TRANSFER_COMPLETED -> orders.transferCompleted(eventId, transferId);
            case TRANSFER_FAILED -> {
                var failed = event.getTransferFailed();
                var reason = failed.getDetail().isBlank()
                        ? failed.getReason().name()
                        : failed.getReason().name() + ": " + failed.getDetail();
                orders.transferFailed(eventId, transferId,
                        reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason);
            }
            default -> throw new IllegalArgumentException("Transfer event " + eventId + " has no payload");
        }
    }
}
