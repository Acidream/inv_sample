package com.example.fixgateway.application;

import com.example.fixgateway.application.port.Broker;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
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
 *
 * <p>Sending and recovery take turns. A status request must not overtake the order it asks about:
 * the broker would answer "unknown", and the order would be sent twice, or refunded while the broker
 * fills it. Both go out through the one session in the order they are handed to it, so a status
 * request that follows the whole send of its order is answered for that order.
 *
 * <p>Nothing is sent after a logon until every status request of that logon is out. Each logon and
 * logout starts a new session period; a send first asks about open orders if that hasn't been done
 * in the current period, so a recovery that failed is tried again before anything else goes out.
 */
@Component
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final int BATCH_SIZE = 50;

    private final GatewayService gateway;
    private final Broker broker;
    /** Only the instance holding the session sends, so a lock in memory is enough. */
    private final ReentrantLock turn = new ReentrantLock();
    /** Counts logons and logouts; a send waits until the status requests of the current period are out. */
    private final AtomicLong sessionPeriod = new AtomicLong();
    private volatile long recoveredPeriod = -1;

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
        turn.lock();
        try {
            if (recoveredPeriod != sessionPeriod.get()) {
                askAboutOpenOrders();
            }
            return send(now);
        } catch (Broker.BrokerUnavailableException e) {
            log.warn("Could not ask the broker about open orders; sending nothing: {}", e.getMessage());
            return 0;
        } finally {
            turn.unlock();
        }
    }

    /** The session logged on or out: what the broker has must be asked about again before sending. */
    public void sessionChanged() {
        sessionPeriod.incrementAndGet();
    }

    private int send(Instant now) {
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

    /**
     * After a logon: ask about every order the broker may have, so no lost report leaves one open.
     * Waits for a send in progress to finish, and holds sending back until every request is out.
     */
    public void recover() {
        turn.lock();
        try {
            askAboutOpenOrders();
        } finally {
            turn.unlock();
        }
    }

    private void askAboutOpenOrders() {
        long period = sessionPeriod.get();
        var open = gateway.withBroker();
        if (!open.isEmpty()) {
            log.info("Asking the broker about {} open orders", open.size());
        }
        for (var order : open) {
            broker.requestStatus(order);
        }
        recoveredPeriod = period;
    }
}
