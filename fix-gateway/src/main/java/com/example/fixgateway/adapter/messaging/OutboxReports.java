package com.example.fixgateway.adapter.messaging;

import com.example.events.broker.v1.BrokerExecution;
import com.example.events.broker.v1.OrderAccepted;
import com.example.events.broker.v1.OrderExpired;
import com.example.events.broker.v1.OrderFilled;
import com.example.events.broker.v1.OrderRejected;
import com.example.fixgateway.application.port.Reports;
import com.example.fixgateway.config.GatewayProperties;
import com.example.platform.messaging.Outbox;
import com.example.platform.messaging.WireValues;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reports to {@code broker.executions.v1} through the outbox, keyed by order id, so investments gets
 * them in the order the broker sent them. Nothing here knows FIX.
 */
@Component
class OutboxReports implements Reports {

    private static final int MAX_REASON_LENGTH = 300;

    private final Outbox outbox;
    private final String topic;

    OutboxReports(Outbox outbox, GatewayProperties properties) {
        this.outbox = outbox;
        this.topic = properties.topics().executions();
    }

    @Override
    public void accepted(String orderId, Instant at) {
        publish(report(orderId, at).setOrderAccepted(OrderAccepted.getDefaultInstance()));
    }

    @Override
    public void filled(String orderId, String execId, BigDecimal quantity, BigDecimal price, boolean done,
            Instant tradedAt, Instant at) {
        publish(report(orderId, at).setOrderFilled(OrderFilled.newBuilder()
                .setExecId(execId)
                .setQuantity(quantity.toPlainString())
                .setPrice(price.toPlainString())
                .setDone(done)
                .setTradedAt(WireValues.timestamp(tradedAt))));
    }

    @Override
    public void rejected(String orderId, String reason, Instant at) {
        var text = reason == null || reason.isBlank() ? "rejected by the broker" : reason;
        publish(report(orderId, at).setOrderRejected(OrderRejected.newBuilder()
                .setReason(WireValues.text(text, MAX_REASON_LENGTH))));
    }

    @Override
    public void expired(String orderId, Instant at) {
        publish(report(orderId, at).setOrderExpired(OrderExpired.getDefaultInstance()));
    }

    private static BrokerExecution.Builder report(String orderId, Instant at) {
        return BrokerExecution.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAt(WireValues.timestamp(at))
                .setOrderId(orderId);
    }

    private void publish(BrokerExecution.Builder report) {
        var built = report.build();
        outbox.add(topic, built.getOrderId(), built.getPayloadCase().name(), built.toByteArray());
    }
}
