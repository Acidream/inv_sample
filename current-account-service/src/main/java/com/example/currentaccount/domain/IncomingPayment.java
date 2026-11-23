package com.example.currentaccount.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Money that arrived from outside the bank for an account, under the payment id the sender chose. */
public record IncomingPayment(String paymentId, UUID accountId, Money amount, Status status, String rejectionReason,
        Instant receivedAt) {

    public enum Status { BOOKED, REJECTED }

    public IncomingPayment {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if ((status == Status.REJECTED) != (rejectionReason != null)) {
            throw new IllegalArgumentException("A rejection reason is given exactly for rejected payments");
        }
    }

    public static IncomingPayment booked(String paymentId, UUID accountId, Money amount, Instant at) {
        return new IncomingPayment(paymentId, accountId, amount, Status.BOOKED, null, at);
    }

    /** The account doesn't exist: in a real bank the money goes back to the sender. */
    public static IncomingPayment toUnknownAccount(String paymentId, UUID accountId, Money amount, Instant at) {
        return new IncomingPayment(paymentId, accountId, amount, Status.REJECTED, TransferAnswer.UNKNOWN_ACCOUNT, at);
    }

    public Optional<String> rejection() {
        return Optional.ofNullable(rejectionReason);
    }

    /** True if this is the same payment: a retry, not a reused payment id. */
    public boolean samePaymentAs(UUID otherAccountId, Money otherAmount) {
        return accountId.equals(otherAccountId) && amount.equals(otherAmount);
    }
}
