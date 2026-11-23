package com.example.savings.application.order;

import java.util.UUID;

public class UnknownOrderException extends RuntimeException {

    public UnknownOrderException(UUID orderId) {
        super("Unknown order " + orderId);
    }
}
