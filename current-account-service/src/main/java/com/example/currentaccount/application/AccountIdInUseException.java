package com.example.currentaccount.application;

import java.util.UUID;

/** The client reused an account id for a different customer or overdraft limit. */
public class AccountIdInUseException extends RuntimeException {

    public AccountIdInUseException(UUID accountId) {
        super("Current account " + accountId + " was already opened with different details");
    }
}
