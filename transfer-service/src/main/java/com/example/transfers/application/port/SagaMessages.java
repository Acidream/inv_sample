package com.example.transfers.application.port;

import com.example.transfers.domain.Step;

/**
 * Sends what a saga step calls for: an instruction to a ledger, or the transfer's outcome. Must be
 * called inside the transaction that recorded the step: the message is sent if and only if that
 * transaction commits.
 */
public interface SagaMessages {

    /** @param step anything but {@link Step.Wait} */
    void send(Step step);
}
