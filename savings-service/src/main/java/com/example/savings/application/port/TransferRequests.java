package com.example.savings.application.port;

import com.example.savings.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Asks the transfer service to move money (ADR 0020). Must be called inside the business transaction:
 * the request is sent if and only if that transaction commits.
 */
public interface TransferRequests {

    /** @param transferId chosen by savings; the transfer service applies a transfer id once */
    record TransferRequest(String transferId, UUID customerId, UUID fromAccountId, UUID toAccountId, Money amount,
            Instant requestedAt) {
    }

    void request(TransferRequest request);
}
