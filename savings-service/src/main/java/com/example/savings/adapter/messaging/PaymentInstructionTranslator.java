package com.example.savings.adapter.messaging;

import com.example.events.payments.v1.PaymentInstruction;
import com.example.savings.application.PaymentInstruction.ReturnTransfer;
import com.example.savings.application.PaymentInstruction.TransferIn;
import com.example.savings.application.PaymentInstruction.TransferOut;
import com.example.savings.domain.Money;
import java.util.UUID;

/**
 * Anti-corruption layer: validates the Payments wire format and turns it into application commands.
 * Anything malformed throws {@link IllegalArgumentException}, which the error handler treats as
 * non-retryable and sends to the dead-letter topic.
 */
final class PaymentInstructionTranslator {

    private PaymentInstructionTranslator() {
    }

    static com.example.savings.application.PaymentInstruction translate(PaymentInstruction message) {
        var eventId = required(message.getEventId(), "event_id");
        var accountId = UUID.fromString(required(message.getAccountId(), "account_id"));
        return switch (message.getPayloadCase()) {
            case TRANSFER_FROM_CURRENT_ACCOUNT -> {
                var transfer = message.getTransferFromCurrentAccount();
                yield new TransferIn(eventId, accountId, required(transfer.getTransferId(), "transfer_id"),
                        money(transfer.getAmount()));
            }
            case TRANSFER_TO_CURRENT_ACCOUNT -> {
                var transfer = message.getTransferToCurrentAccount();
                yield new TransferOut(eventId, accountId, required(transfer.getTransferId(), "transfer_id"),
                        money(transfer.getAmount()));
            }
            case TRANSFER_RETURNED -> new ReturnTransfer(eventId, accountId,
                    required(message.getTransferReturned().getTransferId(), "transfer_id"));
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException("Message " + eventId + " has no payload");
        };
    }

    private static Money money(com.example.events.common.v1.Money money) {
        if (!Money.CURRENCY.equals(money.getCurrency())) {
            throw new IllegalArgumentException("Unsupported currency: " + money.getCurrency());
        }
        return Money.of(money.getAmount()).requirePositive();
    }

    private static String required(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value;
    }
}
