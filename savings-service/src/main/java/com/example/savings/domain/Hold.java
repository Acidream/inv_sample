package com.example.savings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Earmarked funds for one withdrawal request.
 *
 * <pre>
 * RESERVED --confirm--> SETTLED
 * RESERVED --cancel---> RELEASED
 * RESERVED --expire---> EXPIRED --confirm (late)--> SETTLED
 * </pre>
 *
 * <p>Repeating a transition that already happened is a no-op (at-least-once delivery). Anything
 * else is a contract violation and raises {@link IllegalHoldTransitionException}.
 */
@Entity
@Table(name = "hold")
public class Hold {

    public enum Outcome {
        /** The transition happened normally. */
        APPLIED,
        /** Confirmation of an EXPIRED hold: funds were already released, debit anyway. */
        APPLIED_AFTER_EXPIRY,
        /** Already in the target state, nothing to do. */
        NO_OP
    }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, unique = true)
    private String requestId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(nullable = false, updatable = false)
    private Instant reservedAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant resolvedAt;

    @Version
    private Long version;

    protected Hold() {
    }

    public static Hold reserve(UUID accountId, String requestId, Money amount, Instant now, Duration ttl) {
        var hold = new Hold();
        hold.id = UUID.randomUUID();
        hold.accountId = accountId;
        hold.requestId = requestId;
        hold.amount = amount.requirePositive().amount();
        hold.status = HoldStatus.RESERVED;
        hold.reservedAt = now;
        hold.expiresAt = now.plus(ttl);
        return hold;
    }

    public Outcome confirm(Instant now) {
        return switch (status) {
            case RESERVED -> resolve(HoldStatus.SETTLED, now, Outcome.APPLIED);
            case EXPIRED -> resolve(HoldStatus.SETTLED, now, Outcome.APPLIED_AFTER_EXPIRY);
            case SETTLED -> Outcome.NO_OP;
            case RELEASED -> throw new IllegalHoldTransitionException(requestId, status, "confirm");
        };
    }

    public Outcome cancel(Instant now) {
        return switch (status) {
            case RESERVED -> resolve(HoldStatus.RELEASED, now, Outcome.APPLIED);
            case RELEASED, EXPIRED -> Outcome.NO_OP;
            case SETTLED -> throw new IllegalHoldTransitionException(requestId, status, "cancel");
        };
    }

    public Outcome expire(Instant now) {
        if (status != HoldStatus.RESERVED || now.isBefore(expiresAt)) {
            return Outcome.NO_OP;
        }
        return resolve(HoldStatus.EXPIRED, now, Outcome.APPLIED);
    }

    private Outcome resolve(HoldStatus target, Instant now, Outcome outcome) {
        status = target;
        resolvedAt = now;
        return outcome;
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public String requestId() {
        return requestId;
    }

    public Money amount() {
        return new Money(amount);
    }

    public HoldStatus status() {
        return status;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
