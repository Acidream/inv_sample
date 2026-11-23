package com.example.savings.application.port;

import com.example.savings.domain.order.Order;

/**
 * Savings' own command log (ADR 0019). The REST intake appends commands here; savings applies them
 * when it consumes them, so every state change a customer asks for can be replayed.
 */
public interface CommandLog {

    /**
     * Appends an order and returns once the log has stored it.
     *
     * @throws CommandLogUnavailableException if the log didn't confirm it in time; it may still be stored
     *                                        and applied, so the client retries with the same id
     */
    void openAndFund(String commandId, Order.Request request);

    class CommandLogUnavailableException extends RuntimeException {

        public CommandLogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
