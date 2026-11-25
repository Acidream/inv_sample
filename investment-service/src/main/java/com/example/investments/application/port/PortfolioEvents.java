package com.example.investments.application.port;

import com.example.investments.domain.Portfolio;
import com.example.investments.domain.TransferAnswer;

/**
 * What the transfer service hears from portfolios on {@code investments.account-events.v1} (ADR 0028).
 * Must be called inside the transaction that made the change: the outbox sends each event if and
 * only if it commits (ADR 0006).
 */
public interface PortfolioEvents {

    /** {@code PortfolioOpened}, from which the transfer service learns the portfolio. */
    void opened(Portfolio portfolio);

    /** The answer to an instruction of the transfer service. */
    void answered(TransferAnswer answer);
}
