package com.example.currentaccount.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's current account: a balance that may go below zero down to the overdraft limit
 * (ADR 0021). The rules for money leaving the account live here, not in the transfer service: it
 * only learns the answer ({@code CurrentAccountDebited} or {@code DebitRejected}).
 *
 * <ul>
 *   <li>A debit is carried out if the balance after it is not below minus the overdraft limit.
 *   <li>A blocked account refuses transfers both ways. Incoming payments are still booked: the
 *       money has already arrived at the bank.
 *   <li>Money coming in is refused if the balance would exceed {@link Money#MAX_BALANCE}.
 * </ul>
 */
public final class CurrentAccount {

    public enum Status { OPEN, BLOCKED }

    public static final String BLOCKED_REASON = "account blocked";
    public static final String OVERDRAFT_EXCEEDED = "overdraft limit exceeded";
    public static final String BALANCE_LIMIT_EXCEEDED = "balance limit exceeded";

    /** The result of an instruction: the answer, and the booking if money moved. */
    public record Decision(TransferAnswer answer, Optional<Booking> booking) {
    }

    private final UUID id;
    private final UUID customerId;
    private final BigDecimal overdraftLimit;
    private final Instant openedAt;
    private BigDecimal balance;
    private Status status;
    private String blockReason;

    private CurrentAccount(UUID id, UUID customerId, BigDecimal overdraftLimit, Instant openedAt, BigDecimal balance,
            Status status, String blockReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.overdraftLimit = overdraftLimit(overdraftLimit);
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.balance = balance.setScale(Money.SCALE, RoundingMode.UNNECESSARY);
        this.status = Objects.requireNonNull(status, "status");
        this.blockReason = blockReason;
    }

    public static CurrentAccount open(UUID id, UUID customerId, BigDecimal overdraftLimit, Instant at) {
        return new CurrentAccount(id, customerId, overdraftLimit, at, BigDecimal.ZERO, Status.OPEN, null);
    }

    /** Rebuilds an account from storage. */
    public static CurrentAccount restore(UUID id, UUID customerId, BigDecimal overdraftLimit, Instant openedAt,
            BigDecimal balance, Status status, String blockReason) {
        return new CurrentAccount(id, customerId, overdraftLimit, openedAt, balance, status, blockReason);
    }

    /** Validates an overdraft limit: zero or positive, cents at most. */
    public static BigDecimal overdraftLimit(BigDecimal limit) {
        Objects.requireNonNull(limit, "overdraftLimit");
        if (limit.signum() < 0 || limit.stripTrailingZeros().scale() > Money.SCALE) {
            throw new IllegalArgumentException("An overdraft limit is zero or a positive amount in cents: " + limit);
        }
        return limit.setScale(Money.SCALE, RoundingMode.UNNECESSARY);
    }

    public Decision debit(String transferId, Money amount, Instant at) {
        if (status == Status.BLOCKED) {
            return rejected(transferId, TransferAnswer.Direction.DEBIT, amount, BLOCKED_REASON, at);
        }
        var after = balance.subtract(amount.amount());
        if (after.compareTo(overdraftLimit.negate()) < 0) {
            return rejected(transferId, TransferAnswer.Direction.DEBIT, amount, OVERDRAFT_EXCEEDED, at);
        }
        balance = after;
        return new Decision(TransferAnswer.carriedOut(id, transferId, TransferAnswer.Direction.DEBIT, amount, at),
                Optional.of(booking(transferId, Booking.Type.TRANSFER_DEBIT, amount.amount().negate(), at)));
    }

    public Decision credit(String transferId, Money amount, Instant at) {
        if (status == Status.BLOCKED) {
            return rejected(transferId, TransferAnswer.Direction.CREDIT, amount, BLOCKED_REASON, at);
        }
        if (!canTake(amount)) {
            return rejected(transferId, TransferAnswer.Direction.CREDIT, amount, BALANCE_LIMIT_EXCEEDED, at);
        }
        balance = balance.add(amount.amount());
        return new Decision(TransferAnswer.carriedOut(id, transferId, TransferAnswer.Direction.CREDIT, amount, at),
                Optional.of(booking(transferId, Booking.Type.TRANSFER_CREDIT, amount.amount(), at)));
    }

    /** @return the booking, or empty if the balance would exceed {@link Money#MAX_BALANCE} */
    public Optional<Booking> receive(String paymentId, Money amount, Instant at) {
        if (!canTake(amount)) {
            return Optional.empty();
        }
        balance = balance.add(amount.amount());
        return Optional.of(booking(paymentId, Booking.Type.INCOMING_PAYMENT, amount.amount(), at));
    }

    private boolean canTake(Money amount) {
        return balance.add(amount.amount()).compareTo(Money.MAX_BALANCE) <= 0;
    }

    /** Blocking a blocked account only replaces the reason. */
    public void block(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A block needs a reason");
        }
        status = Status.BLOCKED;
        blockReason = reason;
    }

    public void unblock() {
        status = Status.OPEN;
        blockReason = null;
    }

    /** True if opening this account again with these details would change nothing. */
    public boolean openedAs(UUID otherCustomerId, BigDecimal otherOverdraftLimit) {
        return customerId.equals(otherCustomerId) && overdraftLimit.compareTo(otherOverdraftLimit) == 0;
    }

    /** What the customer can still spend: balance plus the unused overdraft. */
    public BigDecimal available() {
        return status == Status.BLOCKED ? BigDecimal.ZERO.setScale(Money.SCALE) : balance.add(overdraftLimit);
    }

    private Decision rejected(String transferId, TransferAnswer.Direction direction, Money amount, String reason,
            Instant at) {
        return new Decision(TransferAnswer.rejected(id, transferId, direction, amount, reason, at), Optional.empty());
    }

    private Booking booking(String reference, Booking.Type type, BigDecimal signedAmount, Instant at) {
        return new Booking(id, reference, type, signedAmount, balance, at);
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public BigDecimal overdraftLimit() {
        return overdraftLimit;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public BigDecimal balance() {
        return balance;
    }

    public Status status() {
        return status;
    }

    public Optional<String> blockReason() {
        return Optional.ofNullable(blockReason);
    }
}
