package com.example.currentaccount.application;

import com.example.currentaccount.domain.Money;
import com.example.currentaccount.domain.TransferAnswer.Direction;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An instruction from the transfer service: debit or credit the account for a transfer.
 *
 * @param messageId the instruction's event id, for de-duplication of redeliveries
 * @param issuedAt  when the transfer service sent it; the booking is dated then
 */
public record Instruction(String messageId, UUID accountId, String transferId, Direction direction, Money amount,
        Instant issuedAt) {

    public Instruction {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
