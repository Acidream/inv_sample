package com.example.savings.adapter.messaging;

import com.example.savings.application.port.TransferRequests;
import com.example.savings.config.SavingsProperties;
import com.example.savings.domain.Money;
import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;

/**
 * Transfer requests to the transfer service, through the {@link Outbox}, keyed by transfer id. The
 * request id is derived from the transfer id, so a replayed order sends the very same request
 * (ADR 0019).
 */
@Component
class OutboxTransferRequestPublisher implements TransferRequests {

    private final Outbox outbox;
    private final String topic;

    OutboxTransferRequestPublisher(Outbox outbox, SavingsProperties properties) {
        this.outbox = outbox;
        this.topic = properties.topics().transferRequests();
    }

    @Override
    public void request(TransferRequests.TransferRequest request) {
        var message = com.example.events.payments.v1.TransferRequest.newBuilder()
                .setRequestId("savings:" + request.transferId())
                .setOccurredAt(Timestamp.newBuilder()
                        .setSeconds(request.requestedAt().getEpochSecond())
                        .setNanos(request.requestedAt().getNano()))
                .setTransferId(request.transferId())
                .setCustomerId(request.customerId().toString())
                .setFromAccountId(request.fromAccountId().toString())
                .setToAccountId(request.toAccountId().toString())
                .setAmount(com.example.events.common.v1.Money.newBuilder()
                        .setAmount(request.amount().toString())
                        .setCurrency(Money.CURRENCY))
                .build();
        outbox.add(topic, request.transferId(), "TRANSFER_REQUEST", message.toByteArray());
    }
}
