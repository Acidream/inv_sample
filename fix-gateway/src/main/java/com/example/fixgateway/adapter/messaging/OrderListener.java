package com.example.fixgateway.adapter.messaging;

import com.example.events.broker.v1.BrokerOrder.PayloadCase;
import com.example.events.broker.v1.PlaceOrder;
import com.example.fixgateway.application.GatewayService;
import com.example.fixgateway.domain.BrokerOrder;
import com.example.platform.messaging.InvalidMessageException;
import com.example.platform.messaging.WireValues;
import com.google.protobuf.InvalidProtocolBufferException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Takes orders from {@code broker.orders.v1}, the gateway's command log (ADR 0027), and checks them
 * at the border: anything malformed is dead-lettered. Nothing here knows FIX.
 */
@Component
class OrderListener {

    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final Pattern QUANTITY = Pattern.compile("\\d{1,18}(\\.\\d{1,6})?");
    /** {@code broker_order.order_id varchar(50)}. */
    private static final int MAX_ORDER_ID_LENGTH = 50;
    /** {@code processed_message.message_id varchar(200)}, less the prefix. */
    private static final int MAX_MESSAGE_ID_LENGTH = 180;

    private final GatewayService gateway;

    OrderListener(GatewayService gateway) {
        this.gateway = gateway;
    }

    @KafkaListener(topics = "${gateway.topics.orders}")
    void onOrder(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        var message = com.example.events.broker.v1.BrokerOrder.parseFrom(record.value());
        if (message.getPayloadCase() != PayloadCase.PLACE_ORDER) {
            throw new InvalidMessageException("Broker order without a known payload");
        }
        gateway.place(WireValues.id(message.getMessageId(), "message_id", MAX_MESSAGE_ID_LENGTH),
                placement(message.getPlaceOrder()),
                WireValues.instant(message.hasOccurredAt() ? message.getOccurredAt() : null, "occurred_at"));
    }

    static BrokerOrder.Placement placement(PlaceOrder order) {
        var orderId = WireValues.id(order.getOrderId(), "order_id", MAX_ORDER_ID_LENGTH);
        if (!ISIN.matcher(order.getIsin()).matches()) {
            throw new InvalidMessageException("Not an ISIN: " + order.getIsin());
        }
        LocalDate day;
        try {
            day = LocalDate.parse(order.getTradingDay());
        } catch (DateTimeParseException e) {
            throw new InvalidMessageException("trading_day is not an ISO date: " + order.getTradingDay(), e);
        }
        return switch (order.getSide()) {
            case BUY -> {
                var currency = order.getCashAmount().getCurrency();
                if (!"EUR".equals(currency)) {
                    throw new InvalidMessageException("Unsupported currency: " + currency);
                }
                var cash = WireValues.plainAmount(order.getCashAmount().getAmount());
                if (cash.signum() <= 0) {
                    throw new InvalidMessageException("A buy needs a positive cash amount");
                }
                yield new BrokerOrder.Placement(orderId, order.getIsin(), BrokerOrder.Side.BUY, cash, null, day);
            }
            case SELL -> {
                var quantity = order.getQuantity();
                if (!QUANTITY.matcher(quantity).matches() || new BigDecimal(quantity).signum() <= 0) {
                    throw new InvalidMessageException("A sell needs a positive quantity: " + quantity);
                }
                yield new BrokerOrder.Placement(orderId, order.getIsin(), BrokerOrder.Side.SELL, null,
                        new BigDecimal(quantity), day);
            }
            case SIDE_UNSPECIFIED, UNRECOGNIZED -> throw new InvalidMessageException("Order without a side");
        };
    }
}
