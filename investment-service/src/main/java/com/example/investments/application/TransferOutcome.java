package com.example.investments.application;

import java.time.Instant;
import java.util.Objects;

/**
 * How a transfer the investment service requested ended, from {@code payments.transfer-events.v1}.
 *
 * @param reason empty if completed
 */
public record TransferOutcome(String messageId, String transferId, boolean completed, String reason, String detail,
        Instant at) {

    public TransferOutcome {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(at, "at");
    }

    /** For people: the reason, and the detail if there is one. */
    public String explanation() {
        return detail.isBlank() ? reason : reason + ": " + detail;
    }
}
