package com.example.savings.adapter.messaging;

import com.example.events.payments.v1.PaymentInstruction;
import com.example.platform.messaging.InvalidMessageException;
import com.example.platform.messaging.WireValues;
import com.example.savings.application.PaymentInstruction.ReturnTransfer;
import com.example.savings.application.PaymentInstruction.TransferIn;
import com.example.savings.application.PaymentInstruction.TransferOut;
import com.example.savings.domain.Money;

/**
 * Anti-corruption layer: validates the Payments wire format and turns it into application commands.
 * Anything malformed throws {@link InvalidMessageException}, which the error handler treats as
 * non-retryable and sends to the dead-letter topic.
 *
 * <p>That includes values the database would refuse: ids longer than their columns, or containing a
 * NUL character, and amounts too large for {@code numeric(19, 2)}. Rejected only at insert, they would
 * surface as a {@code DataIntegrityViolationException}, which is retried without limit (ADR 0007) and
 * would block the partition for good. The technical checks are {@link WireValues}; EUR and positive
 * amounts are savings' rules.
 */
final class PaymentInstructionTranslator {

    /**
     * {@code transfer.transfer_id} is {@code varchar(100)}; event ids are stored as
     * {@code "instruction:" + id} in {@code processed_message.message_id varchar(200)}.
     */
    static final int MAX_ID_LENGTH = 100;

    private PaymentInstructionTranslator() {
    }

    static com.example.savings.application.PaymentInstruction translate(PaymentInstruction message) {
        var eventId = id(message.getEventId(), "event_id");
        var accountId = WireValues.uuid(message.getAccountId(), "account_id");
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
            case PAYLOAD_NOT_SET -> throw new InvalidMessageException("Message " + eventId + " has no payload");
        };
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

    /** An id that fits the {@code varchar(100)} columns. */
    static String id(String value, String field) {
        return WireValues.id(value, field, MAX_ID_LENGTH);
    }
}
