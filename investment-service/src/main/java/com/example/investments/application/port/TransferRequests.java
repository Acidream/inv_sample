package com.example.investments.application.port;

import com.example.investments.domain.TransferRequest;

/**
 * Asks the transfer service to move money between a portfolio and its reference account (ADR 0026).
 * Must be called inside the business transaction: the request is sent if and only if it commits.
 */
public interface TransferRequests {

    void request(TransferRequest request);
}
