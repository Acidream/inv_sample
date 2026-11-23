package com.example.savings.application.port;

import com.example.savings.domain.order.Order;
import java.util.Optional;
import java.util.UUID;

/** Order storage. A changed order must be passed to {@link #update} in the transaction that changed it. */
public interface Orders {

    void add(Order order);

    Optional<Order> find(UUID orderId);

    /** The order whose funding transfer has this id; empty for transfers savings didn't ask for. */
    Optional<Order> findByTransferId(String transferId);

    /** @throws org.springframework.dao.OptimisticLockingFailureException if it was changed concurrently */
    void update(Order order);
}
