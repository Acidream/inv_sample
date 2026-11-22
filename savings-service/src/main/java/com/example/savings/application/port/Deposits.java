package com.example.savings.application.port;

import com.example.savings.domain.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Payments already credited, by their payment id. Guards against the same payment arriving under a
 * new message id, e.g. when Payments re-sends after losing track of what it sent.
 */
public interface Deposits {

    /**
     * Records the deposit unless its payment id is already known. Runs in the caller's transaction.
     *
     * @return the deposit recorded earlier under the same payment id, or empty if this one is new
     */
    Optional<RecordedDeposit> recordIfNew(RecordedDeposit deposit);

    record RecordedDeposit(String paymentId, UUID accountId, Money amount, String eventId, Instant receivedAt) {

        /** Same payment for the same account and amount: a harmless re-send rather than a conflict. */
        public boolean sameMoneyAs(RecordedDeposit other) {
            return accountId.equals(other.accountId) && amount.equals(other.amount);
        }
    }
}
