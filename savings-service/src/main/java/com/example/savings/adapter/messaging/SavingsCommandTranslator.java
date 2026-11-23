package com.example.savings.adapter.messaging;

import static com.example.savings.adapter.messaging.PaymentInstructionTranslator.id;
import static com.example.savings.adapter.messaging.PaymentInstructionTranslator.money;
import static com.example.savings.adapter.messaging.PaymentInstructionTranslator.required;

import com.example.events.savings.v1.OpenAndFund;
import com.example.events.savings.v1.SavingsCommand;
import com.example.savings.domain.Money;
import com.example.savings.domain.order.Order;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Savings' command log, wire format to and from application commands. A malformed command throws
 * {@link IllegalArgumentException} and is dead-lettered.
 */
final class SavingsCommandTranslator {

    /** Stored as {@code "command:" + id} in {@code processed_event.event_id varchar(100)}. */
    static final int MAX_COMMAND_ID_LENGTH = 90;
    static final int MAX_PRODUCT_CODE_LENGTH = 40;

    record Translated(String commandId, Order.Request request) {
    }

    private SavingsCommandTranslator() {
    }

    static Translated translate(SavingsCommand message) {
        var commandId = id(message.getCommandId(), "command_id", MAX_COMMAND_ID_LENGTH);
        if (!message.hasOccurredAt()) {
            throw new IllegalArgumentException("Command " + commandId + " has no occurred_at");
        }
        var placedAt = Instant.ofEpochSecond(message.getOccurredAt().getSeconds(), message.getOccurredAt().getNanos());
        return switch (message.getPayloadCase()) {
            case OPEN_AND_FUND -> {
                var order = message.getOpenAndFund();
                if (order.getProductVersion() < 1) {
                    throw new IllegalArgumentException("Command " + commandId + " has no product_version");
                }
                yield new Translated(commandId, new Order.Request(uuid(order.getOrderId(), "order_id"),
                        uuid(order.getCustomerId(), "customer_id"),
                        id(order.getProductCode(), "product_code", MAX_PRODUCT_CODE_LENGTH), order.getProductVersion(),
                        money(order.getAmount()), uuid(order.getFromAccountId(), "from_account_id"), placedAt));
            }
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException("Command " + commandId + " has no payload");
        };
    }

    static SavingsCommand toMessage(String commandId, Order.Request request) {
        return SavingsCommand.newBuilder()
                .setCommandId(commandId)
                .setOccurredAt(Timestamp.newBuilder()
                        .setSeconds(request.placedAt().getEpochSecond())
                        .setNanos(request.placedAt().getNano()))
                .setOpenAndFund(OpenAndFund.newBuilder()
                        .setOrderId(request.orderId().toString())
                        .setCustomerId(request.customerId().toString())
                        .setProductCode(request.productCode())
                        .setProductVersion(request.productVersion())
                        .setAmount(com.example.events.common.v1.Money.newBuilder()
                                .setAmount(request.amount().toString())
                                .setCurrency(Money.CURRENCY))
                        .setFromAccountId(request.fromAccountId().toString()))
                .build();
    }

    private static UUID uuid(String value, String field) {
        return UUID.fromString(required(value, field));
    }
}
