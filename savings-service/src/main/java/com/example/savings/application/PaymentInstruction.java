package com.example.savings.application;

import com.example.savings.domain.Money;
import java.util.UUID;

/** Commands from the Payments team, already translated from the wire format. */
public sealed interface PaymentInstruction {

    String eventId();

    UUID accountId();

    record Deposit(String eventId, UUID accountId, String paymentId, Money amount) implements PaymentInstruction {
    }

    record RequestWithdrawal(String eventId, UUID accountId, String requestId, Money amount)
            implements PaymentInstruction {
    }

    record ConfirmWithdrawal(String eventId, UUID accountId, String requestId) implements PaymentInstruction {
    }

    record CancelWithdrawal(String eventId, UUID accountId, String requestId) implements PaymentInstruction {
    }
}
