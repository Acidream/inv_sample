package com.example.savings.domain;

/**
 * A booking that would take an account's balance beyond {@link Money#MAX_BALANCE}, the most the
 * ledger can hold. Savings never refuses a credit in the transfer saga (ADR 0020), so a message that
 * causes it is dead-lettered for a person to look at, rather than failing at the database and being
 * retried without end (ADR 0007).
 */
public class BalanceLimitExceededException extends RuntimeException {

    public BalanceLimitExceededException(String message) {
        super(message);
    }
}
