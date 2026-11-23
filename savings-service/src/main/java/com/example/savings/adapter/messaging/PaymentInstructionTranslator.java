package com.example.savings.adapter.messaging;

import com.example.events.payments.v1.PaymentInstruction;
import com.example.savings.application.PaymentInstruction.ReturnTransfer;
import com.example.savings.application.PaymentInstruction.TransferIn;
import com.example.savings.application.PaymentInstruction.TransferOut;
import com.example.savings.domain.Money;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Anti-corruption layer: validates the Payments wire format and turns it into application commands.
 * Anything malformed throws {@link IllegalArgumentException}, which the error handler treats as
 * non-retryable and sends to the dead-letter topic.
 *
 * <p>That includes values the database would refuse: ids longer than their columns, or containing a
 * NUL character, and amounts too large for {@code numeric(19, 2)}. Rejected only at insert, they would
 * surface as a {@code DataIntegrityViolationException}, which is retried without limit (ADR 0007) and
 * would block the partition for good.
 */
final class PaymentInstructionTranslator {

    /** {@code processed_event.event_id} and {@code transfer.transfer_id} are {@code varchar(100)}. */
    static final int MAX_ID_LENGTH = 100;
    /** Digits before the decimal point that fit {@code numeric(19, 2)}. */
    static final int MAX_INTEGER_DIGITS = 17;
    /** Plain decimal notation only: no sign, no exponent, at most two fraction digits. */
    private static final Pattern AMOUNT = Pattern.compile("\\d+(\\.\\d{1,2})?");

    private PaymentInstructionTranslator() {
    }

    static com.example.savings.application.PaymentInstruction translate(PaymentInstruction message) {
        var eventId = id(message.getEventId(), "event_id");
        var accountId = UUID.fromString(required(message.getAccountId(), "account_id"));
        return switch (message.getPayloadCase()) {
            case TRANSFER_FROM_CURRENT_ACCOUNT -> {
                var transfer = message.getTransferFromCurrentAccount();
                yield new TransferIn(eventId, accountId, id(transfer.getTransferId(), "transfer_id"),
                        money(transfer.getAmount()));
            }
            case TRANSFER_TO_CURRENT_ACCOUNT -> {
                var transfer = message.getTransferToCurrentAccount();
                yield new TransferOut(eventId, accountId, id(transfer.getTransferId(), "transfer_id"),
                        money(transfer.getAmount()));
            }
            case TRANSFER_RETURNED -> new ReturnTransfer(eventId, accountId,
                    id(message.getTransferReturned().getTransferId(), "transfer_id"));
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException("Message " + eventId + " has no payload");
        };
    }

    private static Money money(com.example.events.common.v1.Money money) {
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
        return new Money(amount).requirePositive();
    }

    private static String id(String value, String field) {
        required(value, field);
        if (value.codePointCount(0, value.length()) > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(field + " longer than " + MAX_ID_LENGTH + " characters");
        }
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(field + " contains a NUL character");
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value;
    }
}
