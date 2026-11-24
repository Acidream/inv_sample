package com.example.fixgateway.application.port;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What investments hears, on {@code broker.executions.v1}. Must be called inside the transaction
 * that recorded the report: sent if and only if it commits (the outbox, ADR 0006).
 */
public interface Reports {

    void accepted(String orderId, Instant at);

    void filled(String orderId, String execId, BigDecimal quantity, BigDecimal price, boolean done, Instant tradedAt,
            Instant at);

    void rejected(String orderId, String reason, Instant at);

    void expired(String orderId, Instant at);
}
