package com.example.investments.application.port;

import com.example.investments.domain.Order;
import java.time.Instant;

/**
 * Places orders with the broker, through the gateway (ADR 0027). Must be called inside the business
 * transaction: the order goes out if and only if it commits.
 */
public interface Broker {

    void place(Order order, Instant at);
}
