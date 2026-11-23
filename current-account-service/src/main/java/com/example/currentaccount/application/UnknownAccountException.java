package com.example.currentaccount.application;

import java.util.UUID;

public class UnknownAccountException extends RuntimeException {

    public UnknownAccountException(UUID accountId) {
        super("Current account " + accountId + " does not exist (or its opening has not been applied yet)");
    }
}
