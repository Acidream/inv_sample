package com.example.fixgateway.application;

import com.example.fixgateway.application.port.Broker;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends orders to the broker while the session is logged on, and asks about open ones after each
 * logon (ADR 0027). Orders wait in the gateway's table, not in Kafka, while the session is down.
 *
 * <p>Each order is marked {@code SENDING} and committed before it goes out, then {@code SENT}. A crash
 * in between leaves it {@code SENDING}: the status request after the next logon tells whether it
 * arrived, and it is sent again only if it didn't. So an order reaches the broker at most once, unless
 * the broker loses it.
 */
@Component
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final int BATCH_SIZE = 50;

    private final GatewayService gateway;
    private final Broker broker;

    Dispatcher(GatewayService gateway, Broker broker) {
        this.gateway = gateway;
        this.broker = broker;
    }

    /** @return how many orders were sent */
    public int sendWaiting(Instant now) {
        gateway.expireUnsent(now, BATCH_SIZE);
        if (!broker.isLoggedOn()) {
            return 0;
        }
        int sent = 0;
        for (var orderId : gateway.waitingToBeSent(BATCH_SIZE)) {
            var order = gateway.startSending(orderId, now);
            if (order.isEmpty()) {
                continue;
            }
            try {
                broker.place(order.get());
            } catch (Broker.BrokerUnavailableException e) {
                // Stays SENDING: the next logon's status request decides.
                log.warn("Could not hand order {} to the session: {}", orderId, e.getMessage());
                return sent;
            }
            gateway.sent(orderId);
            sent++;
        }
        return sent;
    }

    /** After a logon: ask about every order the broker may have, so no lost report leaves one open. */
    public void recover() {
        var open = gateway.withBroker();
        if (!open.isEmpty()) {
            log.info("Asking the broker about {} open orders", open.size());
        }
        for (var order : open) {
            broker.requestStatus(order);
        }
    }
}
