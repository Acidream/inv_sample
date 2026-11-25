package com.example.investments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A transfer investments asks for, between a portfolio and its reference account (ADR 0026). Built
 * only by the portfolio, so the direction of the money can't be got the wrong way round.
 *
 * @param transferId chosen by investments; the transfer service applies a transfer id once
 */
public record TransferRequest(String transferId, UUID customerId, UUID fromAccountId, UUID toAccountId,
        Money amount, Instant requestedAt) {

    public TransferRequest {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(fromAccountId, "fromAccountId");
        Objects.requireNonNull(toAccountId, "toAccountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
