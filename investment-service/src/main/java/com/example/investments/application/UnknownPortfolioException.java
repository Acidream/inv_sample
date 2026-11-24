package com.example.investments.application;

import java.util.UUID;

/** Also the answer until an accepted opening has been applied: the client polls. */
public class UnknownPortfolioException extends RuntimeException {

    public UnknownPortfolioException(UUID portfolioId) {
        super("Unknown portfolio " + portfolioId);
    }
}
