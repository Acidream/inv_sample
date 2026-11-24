package com.example.fixgateway.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * What the broker reported about an order, translated from a FIX {@code ExecutionReport} by the FIX
 * adapter. Nothing here is FIX: the application decides what investments hears of it.
 */
public sealed interface BrokerEvent {

    String orderId();

    /** The broker has the order. */
    record Accepted(String orderId) implements BrokerEvent {
    }

    /** @param done the order is filled in full */
    record Filled(String orderId, String execId, BigDecimal quantity, BigDecimal price, boolean done,
            Instant tradedAt) implements BrokerEvent {
        public Filled {
            Objects.requireNonNull(execId, "execId");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(tradedAt, "tradedAt");
        }
    }

    /** @param duplicate the broker already had an order under this id: the one sent before arrived */
    record Rejected(String orderId, String reason, boolean duplicate) implements BrokerEvent {
    }

    /** The trading day ended. */
    record Expired(String orderId) implements BrokerEvent {
    }

    /** The broker cancelled the order on its own. */
    record Cancelled(String orderId, String reason) implements BrokerEvent {
    }

    /** The answer to a status request after a logon (ADR 0027). */
    record Status(String orderId, boolean known) implements BrokerEvent {
    }
}
