package com.example.investments.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Money a portfolio owes its reference account: an order's refund or proceeds, or money that arrived
 * without an order waiting for it (ADR 0026). Paid by a transfer the investment service requests. If
 * the current account refuses it, the money comes back and the payout is {@code UNPAID}: retried when
 * the account is unblocked and once a day, never spent on anything else.
 *
 * <p>Each attempt is its own transfer, {@code inv-payout-<payout id>-<attempt>}: a transfer id is
 * applied once, and a failed one stays failed (ADR 0015).
 */
public final class Payout {

    public enum Reason { REFUND, PROCEEDS, UNEXPECTED_DEPOSIT }

    public enum Status { REQUESTED, PAID, UNPAID }

    private static final String PREFIX = "inv-payout-";

    private final UUID id;
    private final UUID portfolioId;
    private final String orderId;
    private final Reason reason;
    private final Money amount;
    private final Instant createdAt;
    private Status status;
    private int attempt;
    private Instant unpaidSince;

    private Payout(UUID id, UUID portfolioId, String orderId, Reason reason, Money amount, Instant createdAt,
            Status status, int attempt, Instant unpaidSince) {
        this.id = Objects.requireNonNull(id, "id");
        this.portfolioId = Objects.requireNonNull(portfolioId, "portfolioId");
        this.orderId = orderId;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = Objects.requireNonNull(status, "status");
        this.attempt = attempt;
        this.unpaidSince = unpaidSince;
        if ((reason == Reason.UNEXPECTED_DEPOSIT) != (orderId == null)) {
            throw new IllegalArgumentException("Only an unexpected deposit is paid out without an order");
        }
    }

    /** An order's refund or proceeds; one per order, so its id is derived from the order's. */
    public static Payout forOrder(UUID portfolioId, String orderId, Reason reason, Money amount, Instant at) {
        return new Payout(uuid("order:" + orderId), portfolioId, orderId, reason, amount, at, Status.REQUESTED, 1,
                null);
    }

    /** Money that arrived under a transfer id no order was waiting for: it goes straight back. */
    public static Payout unexpectedDeposit(UUID portfolioId, String transferId, Money amount, Instant at) {
        return new Payout(uuid("deposit:" + portfolioId + ":" + transferId), portfolioId, null,
                Reason.UNEXPECTED_DEPOSIT, amount, at, Status.REQUESTED, 1, null);
    }

    public static Payout restore(UUID id, UUID portfolioId, String orderId, Reason reason, Money amount,
            Instant createdAt, Status status, int attempt, Instant unpaidSince) {
        return new Payout(id, portfolioId, orderId, reason, amount, createdAt, status, attempt, unpaidSince);
    }

    /** The current attempt's transfer. */
    public String transferId() {
        return PREFIX + id + "-" + attempt;
    }

    public static boolean isPayoutTransfer(String transferId) {
        return transferId.startsWith(PREFIX);
    }

    /** True while this transfer is the payout's current attempt and waits for its money to be taken. */
    public boolean awaits(String transferId, Money withdrawn) {
        return status == Status.REQUESTED && transferId().equals(transferId) && amount.equals(withdrawn);
    }

    public void paid() {
        requireRequested("be paid");
        status = Status.PAID;
        unpaidSince = null;
    }

    public void unpaid(Instant at) {
        requireRequested("be unpaid");
        status = Status.UNPAID;
        if (unpaidSince == null) {
            unpaidSince = at;
        }
    }

    /** Another attempt, under a new transfer id. */
    public void retry() {
        if (status != Status.UNPAID) {
            throw new IllegalStateException("Payout " + id + " is " + status + " and can't be retried");
        }
        status = Status.REQUESTED;
        attempt++;
    }

    private void requireRequested(String action) {
        if (status != Status.REQUESTED) {
            throw new IllegalStateException("Payout " + id + " is " + status + " and can't " + action);
        }
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("payout:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public UUID id() {
        return id;
    }

    public UUID portfolioId() {
        return portfolioId;
    }

    public Optional<String> orderId() {
        return Optional.ofNullable(orderId);
    }

    public Reason reason() {
        return reason;
    }

    public Money amount() {
        return amount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public int attempt() {
        return attempt;
    }

    /** Since when the money has been back on the portfolio, through any retries; empty unless unpaid. */
    public Optional<Instant> unpaidSince() {
        return Optional.ofNullable(unpaidSince);
    }
}
