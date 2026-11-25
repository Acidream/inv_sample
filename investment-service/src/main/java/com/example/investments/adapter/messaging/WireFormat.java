package com.example.investments.adapter.messaging;

import com.example.events.broker.v1.BrokerExecution;
import com.example.events.investments.v1.InvestmentCommand;
import com.example.events.investments.v1.OpenPortfolio;
import com.example.events.investments.v1.PlaceOrder;
import com.example.events.payments.v1.PaymentInstruction;
import com.example.events.payments.v1.TransferEvent;
import com.example.investments.application.BrokerReport;
import com.example.investments.application.Command;
import com.example.investments.application.Instruction;
import com.example.investments.application.TransferOutcome;
import com.example.investments.domain.Execution;
import com.example.investments.domain.Instrument;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Price;
import com.example.investments.domain.Quantity;
import com.example.investments.domain.TransferAnswer.Kind;
import com.example.platform.messaging.InvalidMessageException;
import com.example.platform.messaging.WireValues;
import com.google.protobuf.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Anti-corruption layer for the wire formats: validates what comes in and builds what goes out.
 * Anything malformed throws {@link InvalidMessageException}, which dead-letters the message; the
 * technical checks are {@link WireValues}, the business ones (EUR, positive amounts, quantities and
 * prices within their precision) are here.
 */
final class WireFormat {

    /** {@code transfer_answer.transfer_id varchar(100)}. */
    static final int MAX_TRANSFER_ID_LENGTH = 100;
    /** {@code investment_order.order_id varchar(50)}. */
    static final int MAX_ORDER_ID_LENGTH = 50;
    /** {@code order_fill.exec_id varchar(100)}. */
    static final int MAX_EXEC_ID_LENGTH = 100;
    /** {@code processed_message.message_id varchar(200)}, less the prefix. */
    static final int MAX_MESSAGE_ID_LENGTH = 180;
    static final int MAX_REASON_LENGTH = 300;
    /** {@code numeric(24, 6)} and {@code numeric(19, 4)}. */
    private static final Pattern QUANTITY = Pattern.compile(Quantity.FORMAT);
    private static final Pattern PRICE = Pattern.compile("\\d{1,15}(\\.\\d{1,4})?");

    private WireFormat() {
    }

    // --- commands ---------------------------------------------------------------------------------

    static Command command(InvestmentCommand message) {
        var portfolioId = WireValues.uuid(message.getPortfolioId(), "portfolio_id");
        var at = occurredAt(message.hasOccurredAt(), message.getOccurredAt());
        return switch (message.getPayloadCase()) {
            case OPEN_PORTFOLIO -> new Command.OpenPortfolio(portfolioId,
                    WireValues.uuid(message.getOpenPortfolio().getCustomerId(), "customer_id"),
                    WireValues.uuid(message.getOpenPortfolio().getReferenceAccountId(), "reference_account_id"), at);
            case PLACE_ORDER -> new Command.PlaceOrder(order(portfolioId, message.getPlaceOrder(), at));
            case PAYLOAD_NOT_SET -> throw new InvalidMessageException("Command without payload");
        };
    }

    private static Order.Request order(UUID portfolioId, PlaceOrder order, Instant at) {
        var orderId = WireValues.id(order.getOrderId(), "order_id", MAX_ORDER_ID_LENGTH);
        var isin = isin(order.getIsin());
        return switch (order.getSide()) {
            case BUY -> {
                var amount = money(order.getAmount());
                if (amount.isLessThan(Order.MINIMUM_BUY)) {
                    throw new InvalidMessageException("A buy is at least " + Order.MINIMUM_BUY + ": " + amount);
                }
                if (!order.getQuantity().isEmpty()) {
                    throw new InvalidMessageException("A buy names no quantity");
                }
                yield new Order.Request(orderId, portfolioId, isin, Order.Side.BUY, amount, null, at);
            }
            case SELL -> {
                if (order.hasAmount()) {
                    throw new InvalidMessageException("A sell names no amount");
                }
                yield new Order.Request(orderId, portfolioId, isin, Order.Side.SELL, null,
                        positiveQuantity(order.getQuantity()), at);
            }
            case SIDE_UNSPECIFIED, UNRECOGNIZED -> throw new InvalidMessageException("Order without a side");
        };
    }

    static InvestmentCommand toMessage(String commandId, Command command) {
        var message = InvestmentCommand.newBuilder()
                .setCommandId(commandId)
                .setOccurredAt(WireValues.timestamp(command.acceptedAt()))
                .setPortfolioId(command.portfolioId().toString());
        switch (command) {
            case Command.OpenPortfolio open -> message.setOpenPortfolio(OpenPortfolio.newBuilder()
                    .setCustomerId(open.customerId().toString())
                    .setReferenceAccountId(open.referenceAccountId().toString()));
            case Command.PlaceOrder place -> {
                var request = place.request();
                var order = PlaceOrder.newBuilder()
                        .setOrderId(request.orderId())
                        .setIsin(request.isin())
                        .setSide(PlaceOrder.Side.valueOf(request.side().name()));
                if (request.amount() != null) {
                    order.setAmount(money(request.amount()));
                }
                if (request.quantity() != null) {
                    order.setQuantity(request.quantity().toString());
                }
                message.setPlaceOrder(order);
            }
        }
        return message.build();
    }

    static String commandId(String value) {
        return WireValues.id(value, "command_id", MAX_MESSAGE_ID_LENGTH);
    }

    // --- instructions and transfer outcomes -------------------------------------------------------

    static Instruction instruction(PaymentInstruction message) {
        var messageId = WireValues.id(message.getEventId(), "event_id", MAX_MESSAGE_ID_LENGTH);
        var portfolioId = WireValues.uuid(message.getAccountId(), "account_id");
        var at = occurredAt(message.hasOccurredAt(), message.getOccurredAt());
        return switch (message.getPayloadCase()) {
            case TRANSFER_FROM_CURRENT_ACCOUNT -> new Instruction(messageId, portfolioId,
                    transferId(message.getTransferFromCurrentAccount().getTransferId()), Kind.DEPOSIT,
                    money(message.getTransferFromCurrentAccount().getAmount()), at);
            case TRANSFER_TO_CURRENT_ACCOUNT -> new Instruction(messageId, portfolioId,
                    transferId(message.getTransferToCurrentAccount().getTransferId()), Kind.WITHDRAWAL,
                    money(message.getTransferToCurrentAccount().getAmount()), at);
            case TRANSFER_RETURNED -> new Instruction(messageId, portfolioId,
                    transferId(message.getTransferReturned().getTransferId()), Kind.RETURN, null, at);
            case PAYLOAD_NOT_SET -> throw new InvalidMessageException("Instruction " + messageId + " has no payload");
        };
    }

    /** @return null for an event of a type this service doesn't know (an additive contract change) */
    static TransferOutcome outcome(TransferEvent event) {
        var messageId = WireValues.id(event.getEventId(), "event_id", MAX_MESSAGE_ID_LENGTH);
        var transferId = transferId(event.getTransferId());
        var at = occurredAt(event.hasOccurredAt(), event.getOccurredAt());
        return switch (event.getPayloadCase()) {
            case TRANSFER_COMPLETED -> new TransferOutcome(messageId, transferId, true, "", "", at);
            case TRANSFER_FAILED -> new TransferOutcome(messageId, transferId, false,
                    event.getTransferFailed().getReason().name(),
                    WireValues.text(event.getTransferFailed().getDetail(), MAX_REASON_LENGTH), at);
            case PAYLOAD_NOT_SET -> null;
        };
    }

    // --- broker reports ---------------------------------------------------------------------------

    /** @return null for a report of a type this service doesn't know */
    static BrokerReport report(BrokerExecution message) {
        var messageId = WireValues.id(message.getEventId(), "event_id", MAX_MESSAGE_ID_LENGTH);
        var orderId = WireValues.id(message.getOrderId(), "order_id", MAX_ORDER_ID_LENGTH);
        var at = occurredAt(message.hasOccurredAt(), message.getOccurredAt());
        return switch (message.getPayloadCase()) {
            case ORDER_ACCEPTED -> new BrokerReport.Accepted(messageId, orderId, at);
            case ORDER_FILLED -> {
                var fill = message.getOrderFilled();
                var execution = new Execution(WireValues.id(fill.getExecId(), "exec_id", MAX_EXEC_ID_LENGTH),
                        positiveQuantity(fill.getQuantity()), price(fill.getPrice()),
                        WireValues.instant(fill.hasTradedAt() ? fill.getTradedAt() : null, "traded_at"));
                yield new BrokerReport.Filled(messageId, orderId, execution, fill.getDone(), at);
            }
            case ORDER_REJECTED -> new BrokerReport.Rejected(messageId, orderId,
                    WireValues.text(WireValues.required(message.getOrderRejected().getReason(), "reason"),
                            MAX_REASON_LENGTH), at);
            case ORDER_EXPIRED -> new BrokerReport.Expired(messageId, orderId, at);
            case PAYLOAD_NOT_SET -> null;
        };
    }

    // --- values -----------------------------------------------------------------------------------

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

    private static Quantity positiveQuantity(String raw) {
        if (!QUANTITY.matcher(raw).matches()) {
            throw new InvalidMessageException("Quantity must be a plain decimal with at most 6 fraction digits: "
                    + raw);
        }
        var quantity = new Quantity(new BigDecimal(raw));
        if (!quantity.isPositive()) {
            throw new InvalidMessageException("Quantity must be positive: " + raw);
        }
        return quantity;
    }

    private static Price price(String raw) {
        if (!PRICE.matcher(raw).matches() || new BigDecimal(raw).signum() <= 0) {
            throw new InvalidMessageException("Price must be a positive plain decimal with at most 4 fraction digits: "
                    + raw);
        }
        return new Price(new BigDecimal(raw));
    }

    private static String isin(String value) {
        if (!Instrument.ISIN.matcher(value).matches()) {
            throw new InvalidMessageException("Not an ISIN: " + value);
        }
        return value;
    }

    private static String transferId(String value) {
        return WireValues.id(value, "transfer_id", MAX_TRANSFER_ID_LENGTH);
    }

    /** Every message names when it happened; proto3 can only tell a missing time by its presence. */
    static Instant occurredAt(boolean set, Timestamp occurredAt) {
        return WireValues.instant(set ? occurredAt : null, "occurred_at");
    }
}
