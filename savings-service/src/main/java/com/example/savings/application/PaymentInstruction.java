package com.example.savings.application;

import com.example.savings.domain.Money;
import java.util.UUID;

/**
 * Commands from the Payments team, already translated from the wire format. Money only moves
 * between the customer's current account and savings.
 */
public sealed interface PaymentInstruction {

    String eventId();

    UUID accountId();

    /** The current account was debited; credit savings. */
    record TransferIn(String eventId, UUID accountId, String transferId, Money amount) implements PaymentInstruction {
    }

    /** Move money to the current account, if the balance allows it. */
    record TransferOut(String eventId, UUID accountId, String transferId, Money amount)
            implements PaymentInstruction {
    }

    /** Compensation: the current account refused an earlier transfer out; credit it back. */
    record ReturnTransfer(String eventId, UUID accountId, String transferId) implements PaymentInstruction {
    }
}
