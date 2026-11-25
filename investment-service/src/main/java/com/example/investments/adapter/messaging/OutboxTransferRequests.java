package com.example.investments.adapter.messaging;

import static com.example.investments.adapter.messaging.WireFormat.money;
import static com.example.platform.messaging.WireValues.timestamp;

import com.example.investments.application.port.TransferRequests;
import com.example.investments.config.InvestmentProperties;
import com.example.investments.domain.TransferRequest;
import com.example.platform.messaging.Outbox;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Transfer requests to the transfer service through the shared {@link Outbox}, keyed by transfer id. */
@Component
class OutboxTransferRequests implements TransferRequests {

    private final Outbox outbox;
    private final String topic;

    OutboxTransferRequests(Outbox outbox, InvestmentProperties properties) {
        this.outbox = outbox;
        this.topic = properties.topics().transferRequests();
    }

    @Override
    public void request(TransferRequest request) {
        var message = com.example.events.payments.v1.TransferRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(request.requestedAt()))
                .setTransferId(request.transferId())
                .setCustomerId(request.customerId().toString())
                .setFromAccountId(request.fromAccountId().toString())
                .setToAccountId(request.toAccountId().toString())
                .setAmount(money(request.amount()))
                .build();
        outbox.add(topic, request.transferId(), "TRANSFER_REQUEST", message.toByteArray());
    }
}
