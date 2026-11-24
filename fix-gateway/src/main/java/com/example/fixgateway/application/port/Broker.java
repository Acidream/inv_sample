package com.example.fixgateway.application.port;

import com.example.fixgateway.domain.BrokerOrder;

/** The FIX session to the broker (ADR 0027). */
public interface Broker {

    /** Logged on, so a message sent now goes out at once. */
    boolean isLoggedOn();

    /**
     * Sends a {@code NewOrderSingle}. Once it returns, the message is in the session's store and is
     * resent if the broker asks for it.
     *
     * @throws BrokerUnavailableException if it could not be handed to the session
     */
    void place(BrokerOrder order);

    /** Sends an {@code OrderStatusRequest}; the answer comes as a report. */
    void requestStatus(BrokerOrder order);

    class BrokerUnavailableException extends RuntimeException {

        public BrokerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
