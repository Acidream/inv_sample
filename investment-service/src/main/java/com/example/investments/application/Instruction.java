package com.example.investments.application;

import com.example.investments.domain.Money;
import com.example.investments.domain.TransferAnswer;
import com.example.investments.domain.TransferAnswer.Kind;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An instruction from the transfer service to a portfolio (ADR 0028).
 *
 * @param messageId the instruction's event id, for de-duplication of redeliveries
 * @param amount    null for a {@link Kind#RETURN}, which names only the transfer
 * @param issuedAt  when the transfer service sent it; what it books is dated then
 */
public record Instruction(String messageId, UUID portfolioId, String transferId, Kind kind, Money amount,
        Instant issuedAt) {

    public Instruction {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(issuedAt, "issuedAt");
        if ((amount == null) != (kind == Kind.RETURN)) {
            throw new IllegalArgumentException("Only a return comes without an amount");
        }
    }

    /** This instruction was carried out, for {@code amount}: its own, or for a return what had left. */
    public TransferAnswer carriedOut(Money amount) {
        return new TransferAnswer(portfolioId, transferId, kind, amount, null, issuedAt);
    }

    /** This instruction was refused, for the given reason. */
    public TransferAnswer rejected(String reason) {
        return new TransferAnswer(portfolioId, transferId, kind, amount, reason, issuedAt);
    }
}
