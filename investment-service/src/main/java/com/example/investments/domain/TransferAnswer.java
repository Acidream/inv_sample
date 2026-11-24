package com.example.investments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The portfolio's answer to one instruction of the transfer service, carried out or rejected; kept so
 * the same instruction is answered once (ADR 0007, 0028).
 *
 * @param rejectionReason empty if carried out; only a withdrawal can be rejected
 */
public record TransferAnswer(UUID portfolioId, String transferId, Kind kind, Money amount, String rejectionReason,
        Instant answeredAt) {

    /**
     * Which instruction: {@code TransferFromCurrentAccount}, {@code TransferToCurrentAccount},
     * {@code TransferReturned}.
     */
    public enum Kind { DEPOSIT, WITHDRAWAL, RETURN }

    public static final String NOTHING_OWED = "nothing owed under this transfer id";

    public TransferAnswer {
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(answeredAt, "answeredAt");
        if (rejectionReason != null && kind != Kind.WITHDRAWAL) {
            throw new IllegalArgumentException("Only a withdrawal can be rejected");
        }
    }

    public boolean carriedOut() {
        return rejectionReason == null;
    }

    public Optional<String> rejection() {
        return Optional.ofNullable(rejectionReason);
    }
}
