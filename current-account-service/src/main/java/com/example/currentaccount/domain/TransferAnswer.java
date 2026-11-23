package com.example.currentaccount.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The account's answer to one instruction of the transfer service. Kept, so that the same transfer
 * asked again is recognised and not booked twice.
 *
 * @param rejectionReason free text for people, null if the instruction was carried out
 */
public record TransferAnswer(UUID accountId, String transferId, Direction direction, Money amount,
        String rejectionReason, Instant answeredAt) {

    public enum Direction { DEBIT, CREDIT }

    public static final String UNKNOWN_ACCOUNT = "unknown account";

    public TransferAnswer {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(answeredAt, "answeredAt");
    }

    static TransferAnswer carriedOut(UUID accountId, String transferId, Direction direction, Money amount,
            Instant at) {
        return new TransferAnswer(accountId, transferId, direction, amount, null, at);
    }

    public static TransferAnswer rejected(UUID accountId, String transferId, Direction direction, Money amount,
            String reason, Instant at) {
        return new TransferAnswer(accountId, transferId, direction, amount, Objects.requireNonNull(reason), at);
    }

    public boolean carriedOut() {
        return rejectionReason == null;
    }

    public Optional<String> rejection() {
        return Optional.ofNullable(rejectionReason);
    }

    /** True if this answers the same instruction: a redelivery or a retry, not a reused transfer id. */
    public boolean answers(Direction otherDirection, Money otherAmount) {
        return direction == otherDirection && amount.equals(otherAmount);
    }
}
