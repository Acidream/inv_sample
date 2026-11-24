package com.example.fixgateway.adapter.persistence;

import com.example.fixgateway.application.port.BrokerOrders;
import com.example.fixgateway.domain.BrokerOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcBrokerOrders implements BrokerOrders {

    private static final String SELECT = "select * from broker_order where order_id = :id";

    private final JdbcClient jdbc;

    JdbcBrokerOrders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BrokerOrder> find(String orderId) {
        return jdbc.sql(SELECT).param("id", orderId).query(JdbcBrokerOrders::map).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BrokerOrder> lock(String orderId) {
        return jdbc.sql(SELECT + " for update").param("id", orderId).query(JdbcBrokerOrders::map).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(BrokerOrder order) {
        var placement = order.placement();
        jdbc.sql("""
                insert into broker_order (order_id, isin, side, cash_amount, quantity, trading_day, status, received_at)
                values (:id, :isin, :side, :cash, :quantity, :day, :status, :receivedAt)""")
                .param("id", placement.orderId())
                .param("isin", placement.isin())
                .param("side", placement.side().name())
                .param("cash", placement.cashAmount())
                .param("quantity", placement.quantity())
                .param("day", placement.tradingDay())
                .param("status", order.status().name())
                .param("receivedAt", order.receivedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(BrokerOrder order) {
        jdbc.sql("update broker_order set status = :status where order_id = :id")
                .param("id", order.orderId())
                .param("status", order.status().name())
                .update();
    }

    @Override
    public List<String> waitingToBeSent(int limit) {
        return jdbc.sql("""
                select order_id from broker_order where status = 'RECEIVED' order by received_at limit :limit""")
                .param("limit", limit)
                .query(String.class).list();
    }

    @Override
    public List<String> withBroker() {
        return jdbc.sql("""
                select order_id from broker_order
                where status in ('SENDING', 'SENT', 'ACCEPTED')
                order by received_at""")
                .query(String.class).list();
    }

    @Override
    public long countWithBrokerSince(LocalDate tradingDayBefore) {
        return jdbc.sql("""
                select count(*) from broker_order
                where status in ('SENDING', 'SENT', 'ACCEPTED') and trading_day < :day""")
                .param("day", tradingDayBefore)
                .query(Long.class).single();
    }

    private static BrokerOrder map(ResultSet row, int n) throws SQLException {
        var placement = new BrokerOrder.Placement(row.getString("order_id"), row.getString("isin"),
                BrokerOrder.Side.valueOf(row.getString("side")), row.getBigDecimal("cash_amount"),
                row.getBigDecimal("quantity"), row.getObject("trading_day", LocalDate.class));
        return BrokerOrder.restore(placement, row.getTimestamp("received_at").toInstant(),
                BrokerOrder.Status.valueOf(row.getString("status")));
    }
}
