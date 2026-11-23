package com.example.savings.application.order;

import java.util.UUID;

/** An order id was sent again with a different order. */
public class OrderIdInUseException extends RuntimeException {

    public OrderIdInUseException(UUID orderId) {
        super("Order " + orderId + " was already placed with different details");
    }
}
