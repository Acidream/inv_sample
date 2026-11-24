package com.example.fixgateway.application.port;

import com.example.fixgateway.domain.BrokerOrder;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BrokerOrders {

    Optional<BrokerOrder> find(String orderId);

    /** Finds the order and locks it until the transaction ends. */
    Optional<BrokerOrder> lock(String orderId);

    void add(BrokerOrder order);

    void update(BrokerOrder order);

    /** Ids of orders waiting to be sent, oldest first. */
    List<String> waitingToBeSent(int limit);

    /** Ids of orders the broker may have and hasn't finished. */
    List<String> withBroker();

    /** Orders with the broker whose trading day was before the given one. */
    long countWithBrokerSince(LocalDate tradingDayBefore);
}
