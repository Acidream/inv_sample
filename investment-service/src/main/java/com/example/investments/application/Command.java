package com.example.investments.application;

import com.example.investments.domain.Order;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's request, as it travels through the command log (ADR 0019). Accepted by the REST
 * intake, applied when consumed.
 */
public sealed interface Command {

    UUID portfolioId();

    /** When the request was accepted; decisions are made as of then. */
    Instant acceptedAt();

    record OpenPortfolio(UUID portfolioId, UUID customerId, UUID referenceAccountId, Instant acceptedAt)
            implements Command {
        public OpenPortfolio {
            Objects.requireNonNull(portfolioId, "portfolioId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(referenceAccountId, "referenceAccountId");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }

    record PlaceOrder(Order.Request request) implements Command {
        public PlaceOrder {
            Objects.requireNonNull(request, "request");
        }

        @Override
        public UUID portfolioId() {
            return request.portfolioId();
        }

        @Override
        public Instant acceptedAt() {
            return request.acceptedAt();
        }
    }
}
