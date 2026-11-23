package com.example.currentaccount.application;

import com.example.currentaccount.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to change an account, as it travels through the command log (ADR 0019). Accepted by the
 * REST intake, applied when consumed.
 */
public sealed interface Command {

    UUID accountId();

    /** When the request was accepted; what it books is dated then. */
    Instant acceptedAt();

    record Open(UUID accountId, UUID customerId, BigDecimal overdraftLimit, Instant acceptedAt) implements Command {
        public Open {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(overdraftLimit, "overdraftLimit");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }

    record ReceivePayment(UUID accountId, String paymentId, Money amount, Instant acceptedAt) implements Command {
        public ReceivePayment {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }

    record Block(UUID accountId, String reason, Instant acceptedAt) implements Command {
        public Block {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }

    record Unblock(UUID accountId, Instant acceptedAt) implements Command {
        public Unblock {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }
}
