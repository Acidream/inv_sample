package com.example.investments.application;

import java.util.UUID;

/** The idempotency key that names this portfolio was used for a portfolio with different details. */
public class PortfolioIdInUseException extends RuntimeException {

    public PortfolioIdInUseException(UUID portfolioId) {
        super("Portfolio " + portfolioId + " was already opened with different details");
    }
}
