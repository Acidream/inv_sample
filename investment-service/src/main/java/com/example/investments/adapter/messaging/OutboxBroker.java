package com.example.investments.adapter.messaging;

import static com.example.investments.adapter.messaging.WireFormat.money;
import static com.example.platform.messaging.WireValues.timestamp;

import com.example.events.broker.v1.BrokerOrder;
import com.example.events.broker.v1.PlaceOrder;
import com.example.investments.application.port.Broker;
import com.example.investments.config.InvestmentProperties;
import com.example.investments.domain.Order;
import com.example.platform.messaging.Outbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Orders to the gateway (ADR 0027) through the shared {@link Outbox}, keyed by order id. */
@Component
class OutboxBroker implements Broker {

    private final Outbox outbox;
    private final String topic;

    OutboxBroker(Outbox outbox, InvestmentProperties properties) {
        this.outbox = outbox;
        this.topic = properties.topics().brokerOrders();
    }

    @Override
    public void place(Order order, Instant at) {
        var request = order.request();
        var place = PlaceOrder.newBuilder()
                .setOrderId(order.id())
                .setIsin(order.isin())
                .setSide(PlaceOrder.Side.valueOf(order.side().name()))
                .setTradingDay(order.tradingDay().orElseThrow().toString());
        if (order.side() == Order.Side.BUY) {
            place.setCashAmount(money(request.amount()));
        } else {
            place.setQuantity(request.quantity().toString());
        }
        var message = BrokerOrder.newBuilder()
                .setMessageId(UUID.randomUUID().toString())
                .setOccurredAt(timestamp(at))
                .setPlaceOrder(place)
                .build();
        outbox.add(topic, order.id(), "PLACE_ORDER", message.toByteArray());
    }
}
