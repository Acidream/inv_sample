package com.example.transfers.adapter.messaging;

import com.example.events.payments.v1.TransferRequest;
import com.example.platform.messaging.InvalidMessageException;
import com.example.platform.messaging.WireValues;
import com.example.transfers.domain.Money;
import com.example.transfers.domain.Transfer;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Anti-corruption layer for the wire formats: validates what comes in and builds what goes out.
 * Anything malformed throws {@link InvalidMessageException}, which dead-letters the message,
 * including values the database would refuse (ids longer than their columns, amounts too large).
 */
final class WireFormat {

    /** {@code transfer.transfer_id varchar(100)}. */
    static final int MAX_TRANSFER_ID_LENGTH = 100;
    /** {@code processed_message.message_id varchar(200)}, less the topic prefix. */
    static final int MAX_MESSAGE_ID_LENGTH = 180;
    static final int MAX_DETAIL_LENGTH = 300;

    private WireFormat() {
    }

    static Transfer.Request request(TransferRequest message) {
        return new Transfer.Request(id(message.getTransferId(), "transfer_id", MAX_TRANSFER_ID_LENGTH),
                uuid(message.getCustomerId(), "customer_id"), uuid(message.getFromAccountId(), "from_account_id"),
                uuid(message.getToAccountId(), "to_account_id"), money(message.getAmount()),
                instant(message.hasOccurredAt() ? message.getOccurredAt() : null, "occurred_at"));
    }

    static TransferRequest toMessage(String requestId, Transfer.Request request) {
        return TransferRequest.newBuilder()
                .setRequestId(requestId)
                .setOccurredAt(timestamp(request.requestedAt()))
                .setTransferId(request.transferId())
                .setCustomerId(request.customerId().toString())
                .setFromAccountId(request.fromAccountId().toString())
                .setToAccountId(request.toAccountId().toString())
                .setAmount(money(request.amount()))
                .build();
    }

    static Money money(com.example.events.common.v1.Money money) {
        if (!Money.CURRENCY.equals(money.getCurrency())) {
            throw new InvalidMessageException("Unsupported currency: " + money.getCurrency());
        }
        var amount = WireValues.plainAmount(money.getAmount());
        if (amount.signum() <= 0) {
            throw new InvalidMessageException("Amount must be positive: " + money.getAmount());
        }
        return new Money(amount);
    }

    static com.example.events.common.v1.Money money(Money money) {
        return com.example.events.common.v1.Money.newBuilder()
                .setAmount(money.toString())
                .setCurrency(Money.CURRENCY)
                .build();
    }

    static String messageId(String value) {
        return WireValues.id(value, "event_id", MAX_MESSAGE_ID_LENGTH);
    }

    static String transferId(String value) {
        return WireValues.id(value, "transfer_id", MAX_TRANSFER_ID_LENGTH);
    }

    static UUID uuid(String value, String field) {
        return WireValues.uuid(value, field);
    }

    static String detail(String value) {
        return WireValues.text(value, MAX_DETAIL_LENGTH);
    }

    static Timestamp timestamp(Instant instant) {
        return WireValues.timestamp(instant);
    }

    private static Instant instant(Timestamp timestamp, String field) {
        return WireValues.instant(timestamp, field);
    }

    private static String id(String value, String field, int maxLength) {
        return WireValues.id(value, field, maxLength);
    }
}
