package com.example.savings.adapter.messaging;

import static com.example.savings.adapter.messaging.PaymentInstructionTranslator.id;

import com.example.events.payments.v1.TransferEvent;
import com.example.platform.messaging.WireValues;
import com.example.savings.application.order.OrderService;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Outcomes of transfers; savings cares about the ones that fund its orders (ADR 0020). An event of a
 * type savings doesn't know is skipped, not dead-lettered: Payments adding an event type is an
 * additive contract change (ADR 0005), and savings, compiled against the older contract, sees the
 * new case as an unset payload.
 */
@Component
class TransferEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransferEventListener.class);

    /** Stored as {@code "transfer-event:" + id} in {@code processed_message.message_id varchar(200)}. */
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
        var eventId = WireValues.id(event.getEventId(), "event_id", MAX_EVENT_ID_LENGTH);
        var transferId = id(event.getTransferId(), "transfer_id");
        switch (event.getPayloadCase()) {
            case TRANSFER_COMPLETED -> orders.transferCompleted(eventId, transferId);
            case TRANSFER_FAILED -> {
                var failed = event.getTransferFailed();
                var reason = failed.getDetail().isBlank()
                        ? failed.getReason().name()
                        : failed.getReason().name() + ": " + failed.getDetail();
                orders.transferFailed(eventId, transferId, WireValues.text(reason, MAX_REASON_LENGTH));
            }
            default -> log.debug("Ignoring transfer event {} ({}): not an outcome savings knows", eventId,
                    event.getPayloadCase());
        }
    }
}
