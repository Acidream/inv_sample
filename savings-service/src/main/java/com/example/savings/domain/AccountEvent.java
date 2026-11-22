package com.example.savings.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

/** Facts about an account that other services may react to. Published via the outbox. */
public sealed interface AccountEvent {

    UUID accountId();

    record AccountOpened(UUID accountId, UUID customerId, BigDecimal annualRate) implements AccountEvent {
    }

    record MoneyDeposited(UUID accountId, String paymentId, Money amount, Money balance) implements AccountEvent {
    }

    record FundsReserved(UUID accountId, String requestId, Money amount, Money availableBalance)
            implements AccountEvent {
    }

    record WithdrawalRejected(UUID accountId, String requestId, Money amount, RejectionReason reason)
            implements AccountEvent {
    }

    record MoneyWithdrawn(UUID accountId, String requestId, Money amount, Money balance) implements AccountEvent {
    }

    record FundsReleased(UUID accountId, String requestId, Money amount, ReleaseReason reason)
            implements AccountEvent {
    }

    record InterestPaid(UUID accountId, YearMonth period, Money amount, Money balance) implements AccountEvent {
    }

    enum RejectionReason { INSUFFICIENT_FUNDS }

    enum ReleaseReason { CANCELLED, EXPIRED }
}
