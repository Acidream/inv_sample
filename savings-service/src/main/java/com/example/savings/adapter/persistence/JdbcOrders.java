package com.example.savings.adapter.persistence;

import com.example.savings.application.port.Orders;
import com.example.savings.domain.Money;
import com.example.savings.domain.order.Order;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link Orders} on the {@code savings_order} table (V8). */
@Component
class JdbcOrders implements Orders {

    private final JdbcClient jdbc;

    JdbcOrders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(Order order) {
        var state = order.state();
        var request = state.request();
        jdbc.sql("""
                insert into savings_order (order_id, customer_id, product_code, product_version, amount,
                    from_account_id, placed_at, status, rejection_reason, account_id, transfer_id, failure_reason,
                    version)
                values (:orderId, :customerId, :productCode, :productVersion, :amount, :fromAccountId, :placedAt,
                    :status, :rejectionReason, :accountId, :transferId, :failureReason, 0)""")
                .param("orderId", request.orderId())
                .param("customerId", request.customerId())
                .param("productCode", request.productCode())
                .param("productVersion", request.productVersion())
                .param("amount", request.amount().amount())
                .param("fromAccountId", request.fromAccountId())
                .param("placedAt", request.placedAt().atOffset(ZoneOffset.UTC))
                .param("status", state.status().name())
                .param("rejectionReason", state.rejectionReason() == null ? null : state.rejectionReason().name())
                .param("accountId", state.accountId())
                .param("transferId", state.transferId())
                .param("failureReason", state.failureReason())
                .update();
    }

    @Override
    public Optional<Order> find(UUID orderId) {
        return jdbc.sql("select * from savings_order where order_id = :id").param("id", orderId)
                .query(JdbcOrders::map).optional();
    }

    @Override
    public Optional<Order> findByTransferId(String transferId) {
        return jdbc.sql("select * from savings_order where transfer_id = :id").param("id", transferId)
                .query(JdbcOrders::map).optional();
    }

    /** Only the status and the failure reason change once an order is placed. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Order order) {
        var state = order.state();
        int updated = jdbc.sql("""
                update savings_order set status = :status, failure_reason = :failureReason, version = version + 1
                where order_id = :orderId and version = :version""")
                .param("status", state.status().name())
                .param("failureReason", state.failureReason())
                .param("orderId", state.request().orderId())
                .param("version", state.version())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Order " + state.request().orderId()
                    + " was changed concurrently");
        }
    }

    private static Order map(ResultSet row, int rowNum) throws SQLException {
        var request = new Order.Request(row.getObject("order_id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getString("product_code"), row.getInt("product_version"), new Money(row.getBigDecimal("amount")),
                row.getObject("from_account_id", UUID.class), row.getTimestamp("placed_at").toInstant());
        var rejection = row.getString("rejection_reason");
        return Order.restore(new Order.State(request, Order.Status.valueOf(row.getString("status")),
                rejection == null ? null : Order.RejectionReason.valueOf(rejection),
                row.getObject("account_id", UUID.class), row.getString("transfer_id"), row.getString("failure_reason"),
                row.getLong("version")));
    }
}
