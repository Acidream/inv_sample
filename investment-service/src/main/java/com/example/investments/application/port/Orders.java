package com.example.investments.application.port;

import com.example.investments.domain.Fill;
import com.example.investments.domain.Order;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface Orders {

    Optional<Order> find(String orderId);

    void add(Order order);

    void update(Order order);

    Optional<Fill> findFill(String orderId, String execId);

    void addFill(String orderId, Fill fill);

    /** Oldest first. */
    List<Fill> fills(String orderId);

    /** Orders still with the broker whose trading day was before the given one (ADR 0027). */
    long countTradingSince(LocalDate tradingDayBefore);
}
