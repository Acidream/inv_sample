package com.example.savings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A movement between the customer's current account and this savings account, identified by the id
 * the Payments team gave it. Remembering every transfer makes a re-sent instruction harmless and
 * tells a return which withdrawal it compensates.
 *
 * <pre>
 *   IN:   COMPLETED
 *   OUT:  COMPLETED ──return──▶ RETURNED
 *         REJECTED   (insufficient funds; a new attempt needs a new transfer id)
 * </pre>
 */
@Entity
@Table(name = "transfer")
public class Transfer {

    public enum Direction {
        IN,
        OUT
    }

    public enum Status {
        COMPLETED,
        REJECTED,
        RETURNED
    }

    @Id
    private String transferId;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Direction direction;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant returnedAt;

    @Version
    private Long version;

    protected Transfer() {
    }

    private Transfer(String transferId, UUID accountId, Direction direction, Money amount, Status status,
            Instant now) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.direction = direction;
        this.amount = amount.requirePositive().amount();
        this.status = status;
        this.createdAt = now;
    }

    public static Transfer deposited(String transferId, UUID accountId, Money amount, Instant now) {
        return new Transfer(transferId, accountId, Direction.IN, amount, Status.COMPLETED, now);
    }

    public static Transfer withdrawn(String transferId, UUID accountId, Money amount, Instant now) {
        return new Transfer(transferId, accountId, Direction.OUT, amount, Status.COMPLETED, now);
    }

    public static Transfer rejected(String transferId, UUID accountId, Money amount, Instant now) {
        return new Transfer(transferId, accountId, Direction.OUT, amount, Status.REJECTED, now);
    }

    /**
     * The current account refused a withdrawal and the money comes back.
     *
     * @return false if the return was already applied (a re-sent return is harmless)
     * @throws IllegalTransferException if this transfer can't be returned: a deposit, or a withdrawal
     *                                  that was rejected and never left
     */
    public boolean markReturned(Instant now) {
        if (status == Status.RETURNED) {
            return false;
        }
        if (direction != Direction.OUT || status != Status.COMPLETED) {
            throw new IllegalTransferException("Transfer " + transferId + " (" + direction + ", " + status
                    + ") cannot be returned");
        }
        status = Status.RETURNED;
        returnedAt = now;
        return true;
    }

    /** The same transfer id for the same movement: a harmless re-send rather than a conflict. */
    public boolean describes(Direction direction, UUID accountId, Money amount) {
        return this.direction == direction && this.accountId.equals(accountId) && amount().equals(amount);
    }

    public String transferId() {
        return transferId;
    }

    public UUID accountId() {
        return accountId;
    }

    public Direction direction() {
        return direction;
    }

    public Money amount() {
        return new Money(amount);
    }

    public Status status() {
        return status;
    }
}
