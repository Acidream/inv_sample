package com.example.savings.domain.order;

import com.example.savings.domain.Money;
import com.example.savings.domain.SavingsProduct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's order to open an account on a product version and fund it from their current account
 * (ADR 0020). The account is opened when the order is placed, so its terms are fixed before any money
 * moves; the order then follows the transfer that funds it.
 *
 * <pre>
 * REJECTED  &lt;-- placed --&gt;  FUNDING  --&gt;  FUNDED
 *                                    \--&gt;  FUNDING_FAILED
 * </pre>
 */
public final class Order {

    public enum Status { REJECTED, FUNDING, FUNDED, FUNDING_FAILED }

    public enum RejectionReason {
        /** No such product, or it isn't sold any more. */
        PRODUCT_NOT_ON_SALE,
        /** The customer accepted terms that have since been replaced by a newer version. */
        PRODUCT_VERSION_NOT_CURRENT
    }

    /** What the customer asked for. {@code placedAt} is when; decisions are made as of then. */
    public record Request(UUID orderId, UUID customerId, String productCode, int productVersion, Money amount,
            UUID fromAccountId, Instant placedAt) {

        public Request {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(productCode, "productCode");
            Objects.requireNonNull(amount, "amount").requirePositive();
            Objects.requireNonNull(fromAccountId, "fromAccountId");
            Objects.requireNonNull(placedAt, "placedAt");
        }

        /** The same order, placed again: everything but the time it was sent. */
        public boolean sameOrderAs(Request other) {
            return orderId.equals(other.orderId) && customerId.equals(other.customerId)
                    && productCode.equals(other.productCode) && productVersion == other.productVersion
                    && amount.equals(other.amount) && fromAccountId.equals(other.fromAccountId);
        }
    }

    /** Everything that is stored. {@code version} is the optimistic-concurrency token (null until stored). */
    public record State(Request request, Status status, RejectionReason rejectionReason, UUID accountId,
            String transferId, String failureReason, Long version) {
    }

    private final Request request;
    private final RejectionReason rejectionReason;
    private final UUID accountId;
    private final String transferId;
    private final Long version;
    private Status status;
    private String failureReason;

    private Order(State state) {
        this.request = Objects.requireNonNull(state.request(), "request");
        this.status = Objects.requireNonNull(state.status(), "status");
        this.rejectionReason = state.rejectionReason();
        this.accountId = state.accountId();
        this.transferId = state.transferId();
        this.failureReason = state.failureReason();
        this.version = state.version();
        if ((status == Status.REJECTED) != (rejectionReason != null)) {
            throw new IllegalArgumentException("Only a rejected order has a rejection reason: " + state);
        }
        if ((status == Status.REJECTED) == (accountId != null && transferId != null)) {
            throw new IllegalArgumentException("Exactly the accepted orders have an account and a transfer: " + state);
        }
    }

    /**
     * Decides an order. It is accepted only if the product is on sale and the version the customer
     * accepted is still its current one.
     *
     * @param currentVersion the product's current version when the order was placed, empty if not on sale
     */
    public static Order place(Request request, Optional<SavingsProduct> currentVersion) {
        if (currentVersion.isEmpty() || !currentVersion.get().code().equals(request.productCode())) {
            return new Order(new State(request, Status.REJECTED, RejectionReason.PRODUCT_NOT_ON_SALE, null, null,
                    null, null));
        }
        if (currentVersion.get().version() != request.productVersion()) {
            return new Order(new State(request, Status.REJECTED, RejectionReason.PRODUCT_VERSION_NOT_CURRENT, null,
                    null, null, null));
        }
        return new Order(new State(request, Status.FUNDING, null, accountIdFor(request.orderId()),
                transferIdFor(request.orderId()), null, null));
    }

    public static Order restore(State state) {
        return new Order(state);
    }

    /** Name-based, so the same order opens the same account on a redelivery or replay (ADR 0019). */
    public static UUID accountIdFor(UUID orderId) {
        return UUID.nameUUIDFromBytes(("savings-account:" + orderId).getBytes(StandardCharsets.UTF_8));
    }

    public static String transferIdFor(UUID orderId) {
        return "savings-order-" + orderId;
    }

    /** The funding transfer completed. Repeating it changes nothing. */
    public void funded() {
        if (status == Status.FUNDED) {
            return;
        }
        requireFunding("funded");
        status = Status.FUNDED;
    }

    /** The funding transfer failed: no money moved. The account stays open with a zero balance. */
    public void fundingFailed(String reason) {
        if (status == Status.FUNDING_FAILED) {
            return;
        }
        requireFunding("failed");
        status = Status.FUNDING_FAILED;
        failureReason = Objects.requireNonNull(reason, "reason");
    }

    private void requireFunding(String outcome) {
        if (status != Status.FUNDING) {
            throw new IllegalOrderTransitionException("Order " + request.orderId() + " is " + status
                    + "; its transfer can't have " + outcome);
        }
    }

    public State state() {
        return new State(request, status, rejectionReason, accountId, transferId, failureReason, version);
    }

    public UUID orderId() {
        return request.orderId();
    }

    public Request request() {
        return request;
    }

    public Status status() {
        return status;
    }

    public Optional<RejectionReason> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    public Optional<UUID> accountId() {
        return Optional.ofNullable(accountId);
    }

    public Optional<String> transferId() {
        return Optional.ofNullable(transferId);
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }
}
