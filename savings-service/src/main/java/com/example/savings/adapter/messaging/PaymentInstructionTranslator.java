package com.example.savings.adapter.messaging;

import com.example.events.payments.v1.PaymentInstruction;
import com.example.savings.application.PaymentInstruction.CancelWithdrawal;
import com.example.savings.application.PaymentInstruction.ConfirmWithdrawal;
import com.example.savings.application.PaymentInstruction.Deposit;
import com.example.savings.application.PaymentInstruction.RequestWithdrawal;
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
            case PAYMENT_RECEIVED -> {
                var payment = message.getPaymentReceived();
                yield new Deposit(eventId, accountId, required(payment.getPaymentId(), "payment_id"),
                        money(payment.getAmount()));
            }
            case WITHDRAWAL_REQUESTED -> {
                var request = message.getWithdrawalRequested();
                yield new RequestWithdrawal(eventId, accountId, required(request.getRequestId(), "request_id"),
                        money(request.getAmount()));
            }
            case WITHDRAWAL_CONFIRMED -> new ConfirmWithdrawal(eventId, accountId,
                    required(message.getWithdrawalConfirmed().getRequestId(), "request_id"));
            case WITHDRAWAL_CANCELLED -> new CancelWithdrawal(eventId, accountId,
                    required(message.getWithdrawalCancelled().getRequestId(), "request_id"));
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
