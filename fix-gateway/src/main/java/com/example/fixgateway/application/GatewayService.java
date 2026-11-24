package com.example.fixgateway.application;

import com.example.fixgateway.application.port.BrokerOrders;
import com.example.fixgateway.application.port.Executions;
import com.example.fixgateway.application.port.ProcessedMessages;
import com.example.fixgateway.application.port.Reports;
import com.example.fixgateway.domain.BrokerOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway's records of orders and what the broker reported (ADR 0027), one transaction each.
 * Sending is the {@link Dispatcher}'s job: the network never runs inside a transaction.
 *
 * <p>Investments hears every report once: a re-sent order is placed once (order id), a resent fill is
 * published once (exec id), and every order ends in exactly one of a final fill, a rejection or an
 * expiry.
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    static final String UNKNOWN_AT_BROKER = "the broker does not know the order";

    private final BrokerOrders orders;
    private final Executions executions;
    private final ProcessedMessages processed;
    private final Reports reports;

    public GatewayService(BrokerOrders orders, Executions executions, ProcessedMessages processed, Reports reports) {
        this.orders = orders;
        this.executions = executions;
        this.processed = processed;
        this.reports = reports;
    }

    /**
     * Takes an order from investments. It is sent once the session allows.
     *
     * @throws OrderIdInUseException if the order id was placed before with other details (dead-lettered)
     */
    @Transactional
    public void place(String messageId, BrokerOrder.Placement placement, Instant at) {
        if (!processed.markProcessed("order:" + messageId)) {
            return;
        }
        var existing = orders.lock(placement.orderId());
        if (existing.isPresent()) {
            if (!existing.get().placement().samePlacementAs(placement)) {
                throw new OrderIdInUseException(placement.orderId());
            }
            log.info("Order {} was already taken; skipping message {}", placement.orderId(), messageId);
            return;
        }
        orders.add(BrokerOrder.received(placement, at));
    }

    // --- sending, for the dispatcher --------------------------------------------------------------

    /**
     * Marks an order as being sent, and commits that before it goes out. An order whose trading day
     * ended before it could be sent expires without ever reaching the broker.
     *
     * @return the order to send, if it is still waiting and its day hasn't ended
     */
    @Transactional
    public Optional<BrokerOrder> startSending(String orderId, Instant now) {
        var order = orders.lock(orderId).filter(found -> found.status() == BrokerOrder.Status.RECEIVED);
        if (order.isEmpty()) {
            return Optional.empty();
        }
        if (order.get().tradingDayEndedAt(now)) {
            expireUnsent(order.get(), now);
            return Optional.empty();
        }
        order.get().sending();
        orders.update(order.get());
        return order;
    }

    @Transactional
    public void sent(String orderId) {
        orders.lock(orderId).filter(order -> order.status() == BrokerOrder.Status.SENDING).ifPresent(order -> {
            order.sent();
            orders.update(order);
        });
    }

    /** Orders still waiting when their day ended, e.g. because the session was down all day. */
    @Transactional
    public int expireUnsent(Instant now, int limit) {
        int expired = 0;
        for (var orderId : orders.waitingToBeSent(limit)) {
            var order = orders.lock(orderId).filter(found -> found.status() == BrokerOrder.Status.RECEIVED);
            if (order.isPresent() && order.get().tradingDayEndedAt(now)) {
                expireUnsent(order.get(), now);
                expired++;
            }
        }
        return expired;
    }

    private void expireUnsent(BrokerOrder order, Instant now) {
        log.warn("Order {} expired before it could be sent: its trading day {} ended", order.orderId(),
                order.placement().tradingDay());
        order.done();
        orders.update(order);
        reports.expired(order.orderId(), now);
    }

    @Transactional(readOnly = true)
    public List<String> waitingToBeSent(int limit) {
        return orders.waitingToBeSent(limit);
    }

    @Transactional(readOnly = true)
    public List<BrokerOrder> withBroker() {
        return orders.withBroker().stream().flatMap(id -> orders.find(id).stream()).toList();
    }

    // --- reports from the broker ------------------------------------------------------------------

    /** Applies one report from the broker and publishes what investments needs to hear of it. */
    @Transactional
    public void report(BrokerEvent event, Instant at) {
        var found = orders.lock(event.orderId());
        if (found.isEmpty()) {
            log.warn("Broker reported {} for unknown order {}; ignored", event.getClass().getSimpleName(),
                    event.orderId());
            return;
        }
        var order = found.get();
        switch (event) {
            case BrokerEvent.Accepted accepted -> {
                if (order.accepted()) {
                    reports.accepted(order.orderId(), at);
                }
            }
            case BrokerEvent.Filled fill -> filled(order, fill, at);
            case BrokerEvent.Rejected rejected -> rejected(order, rejected, at);
            case BrokerEvent.Expired expired -> end(order, () -> reports.expired(order.orderId(), at));
            case BrokerEvent.Cancelled cancelled -> end(order, () -> reports.rejected(order.orderId(),
                    "cancelled by the broker" + (cancelled.reason().isBlank() ? "" : ": " + cancelled.reason()), at));
            case BrokerEvent.Status status -> status(order, status, at);
        }
        orders.update(order);
    }

    private void filled(BrokerOrder order, BrokerEvent.Filled fill, Instant at) {
        if (!executions.markReported(order.orderId(), fill.execId())) {
            log.info("Execution {} of order {} was reported before; skipping", fill.execId(), order.orderId());
            return;
        }
        if (order.isDone()) {
            // Investments dead-letters it: a fill after the end needs a person, not silence.
            log.error("Broker reported fill {} for order {}, which had ended", fill.execId(), order.orderId());
        }
        order.accepted();
        reports.filled(order.orderId(), fill.execId(), fill.quantity(), fill.price(), fill.done(), fill.tradedAt(),
                at);
        if (fill.done()) {
            order.done();
        }
    }

    private void rejected(BrokerOrder order, BrokerEvent.Rejected rejected, Instant at) {
        if (rejected.duplicate() && order.status().isWithBroker()) {
            // Sent again after a crash; the broker has the first one, which goes on as normal.
            log.info("Broker already has order {}; the duplicate was rejected", order.orderId());
            order.accepted();
            return;
        }
        end(order, () -> reports.rejected(order.orderId(), rejected.reason(), at));
    }

    /**
     * A status after a logon. An order whose sending was interrupted and that the broker doesn't know
     * never left: it is sent again. One the broker had and lost is rejected, so its money is freed.
     */
    private void status(BrokerOrder order, BrokerEvent.Status status, Instant at) {
        if (status.known()) {
            order.accepted();
            return;
        }
        if (order.status() == BrokerOrder.Status.SENDING) {
            log.info("Order {} never reached the broker; sending it again", order.orderId());
            order.neverArrived();
        } else if (order.status().isWithBroker()) {
            log.error("Broker does not know order {}, which it had accepted", order.orderId());
            end(order, () -> reports.rejected(order.orderId(), UNKNOWN_AT_BROKER, at));
        }
    }

    private void end(BrokerOrder order, Runnable report) {
        if (order.isDone()) {
            log.info("Order {} had already ended; final report ignored", order.orderId());
            return;
        }
        report.run();
        order.done();
    }

    // --- monitoring -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public long countOverdue(LocalDate today) {
        return orders.countWithBrokerSince(today);
    }
}
