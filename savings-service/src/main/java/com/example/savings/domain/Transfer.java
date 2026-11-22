package com.example.savings.domain;

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
 *
 * <p>Plain Java, stored through {@link State} (ADR 0016); {@code version} as on {@link Account}.
 */
public final class Transfer {

    public enum Direction {
        IN,
        OUT
    }

    public enum Status {
        COMPLETED,
        REJECTED,
        RETURNED
    }

    /** Everything that is stored, for persistence to map; invariants are checked on {@link #restore}. */
    public record State(String transferId, UUID accountId, Direction direction, Money amount, Status status,
            Instant createdAt, Instant returnedAt, Long version) {
    }

    private final String transferId;
    private final UUID accountId;
    private final Direction direction;
    private final Money amount;
    private final Instant createdAt;
    private final Long version;
    private Status status;
    private Instant returnedAt;

    private Transfer(State state) {
        this.transferId = Objects.requireNonNull(state.transferId(), "transferId");
        this.accountId = Objects.requireNonNull(state.accountId(), "accountId");
        this.direction = Objects.requireNonNull(state.direction(), "direction");
        this.amount = state.amount().requirePositive();
        this.status = Objects.requireNonNull(state.status(), "status");
        this.createdAt = Objects.requireNonNull(state.createdAt(), "createdAt");
        this.returnedAt = state.returnedAt();
        this.version = state.version();
        if (status == Status.RETURNED && (direction != Direction.OUT || returnedAt == null)) {
            throw new IllegalArgumentException("Only a withdrawal can be returned, with a return time: " + state);
        }
    }

    private static Transfer create(String transferId, UUID accountId, Direction direction, Money amount,
            Status status, Instant now) {
        return new Transfer(new State(transferId, accountId, direction, amount, status, now, null, null));
    }

    public static Transfer deposited(String transferId, UUID accountId, Money amount, Instant now) {
        return create(transferId, accountId, Direction.IN, amount, Status.COMPLETED, now);
    }

    public static Transfer withdrawn(String transferId, UUID accountId, Money amount, Instant now) {
        return create(transferId, accountId, Direction.OUT, amount, Status.COMPLETED, now);
    }

    public static Transfer rejected(String transferId, UUID accountId, Money amount, Instant now) {
        return create(transferId, accountId, Direction.OUT, amount, Status.REJECTED, now);
    }

    /** Rebuilds a stored transfer. */
    public static Transfer restore(State state) {
        return new Transfer(state);
    }

    public State state() {
        return new State(transferId, accountId, direction, amount, status, createdAt, returnedAt, version);
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
        return this.direction == direction && this.accountId.equals(accountId) && this.amount.equals(amount);
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
        return amount;
    }

    public Status status() {
        return status;
    }
}
