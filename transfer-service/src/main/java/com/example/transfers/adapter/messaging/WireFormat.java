package com.example.transfers.adapter.messaging;

import com.example.events.payments.v1.TransferRequest;
import com.example.transfers.domain.Money;
import com.example.transfers.domain.Transfer;
import com.google.protobuf.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Anti-corruption layer for the wire formats: validates what comes in and builds what goes out.
 * Anything malformed throws {@link IllegalArgumentException}, which dead-letters the message,
 * including values the database would refuse (ids longer than their columns, amounts too large).
 */
final class WireFormat {

    /** {@code transfer.transfer_id varchar(100)}. */
    static final int MAX_TRANSFER_ID_LENGTH = 100;
    /** {@code processed_message.message_id varchar(200)}, less the topic prefix. */
    static final int MAX_MESSAGE_ID_LENGTH = 180;
    static final int MAX_INTEGER_DIGITS = 17;
    static final int MAX_DETAIL_LENGTH = 300;
    private static final Pattern AMOUNT = Pattern.compile("\\d+(\\.\\d{1,2})?");

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
            throw new IllegalArgumentException("Unsupported currency: " + money.getCurrency());
        }
        var raw = money.getAmount();
        if (!AMOUNT.matcher(raw).matches()) {
            throw new IllegalArgumentException("Amount must be a plain decimal with at most two fraction digits: "
                    + raw);
        }
        var amount = new BigDecimal(raw);
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("Amount too large: " + raw);
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
        return id(value, "event_id", MAX_MESSAGE_ID_LENGTH);
    }

    static String transferId(String value) {
        return id(value, "transfer_id", MAX_TRANSFER_ID_LENGTH);
    }

    static UUID uuid(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return UUID.fromString(value);
    }

    static String detail(String value) {
        return value.length() > MAX_DETAIL_LENGTH ? value.substring(0, MAX_DETAIL_LENGTH) : value;
    }

    static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static Instant instant(Timestamp timestamp, String field) {
        if (timestamp == null) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static String id(String value, String field, int maxLength) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(field + " longer than " + maxLength + " characters");
        }
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(field + " contains a NUL character");
        }
        return value;
    }
}
