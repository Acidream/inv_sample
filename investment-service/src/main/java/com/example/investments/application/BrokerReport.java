package com.example.investments.application;

import com.example.investments.domain.Execution;
import java.time.Instant;
import java.util.Objects;

/**
 * What the broker reported about an order, through the gateway (ADR 0027).
 *
 * <p>{@code messageId} de-duplicates redeliveries; a fill is also applied once per {@code execId}.
 */
public sealed interface BrokerReport {

    String messageId();

    String orderId();

    Instant at();

    record Accepted(String messageId, String orderId, Instant at) implements BrokerReport {
    }

    /** {@code at} is when the gateway heard of it; the execution has the broker's own trade time. */
    record Filled(String messageId, String orderId, Execution execution, boolean done, Instant at)
            implements BrokerReport {
        public Filled {
            Objects.requireNonNull(execution, "execution");
        }
    }

    record Rejected(String messageId, String orderId, String reason, Instant at) implements BrokerReport {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Expired(String messageId, String orderId, Instant at) implements BrokerReport {
    }
}
