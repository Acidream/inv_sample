package com.example.investments.adapter.persistence;

import com.example.investments.application.port.Orders;
import com.example.investments.domain.Fill;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Price;
import com.example.investments.domain.Quantity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Orders and their fills. Writers hold the portfolio's lock (ADR 0026), which serialises their changes. */
@Component
class JdbcOrders implements Orders {

    private final JdbcClient jdbc;

    JdbcOrders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Order> find(String orderId) {
        return jdbc.sql("select * from investment_order where order_id = :id").param("id", orderId)
                .query(JdbcOrders::map).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(Order order) {
        var state = order.state();
        var request = state.request();
        jdbc.sql("""
                insert into investment_order (order_id, portfolio_id, isin, side, amount, quantity, status, settlement,
                    trading_day, filled_quantity, filled_value, rejection_reason, accepted_at)
                values (:id, :portfolio, :isin, :side, :amount, :quantity, :status, :settlement, :tradingDay,
                    :filledQuantity, :filledValue, :reason, :acceptedAt)""")
                .param("id", request.orderId())
                .param("portfolio", request.portfolioId())
                .param("isin", request.isin())
                .param("side", request.side().name())
                .param("amount", request.amount() == null ? null : request.amount().amount())
                .param("quantity", request.quantity() == null ? null : request.quantity().value())
                .param("status", state.status().name())
                .param("settlement", state.settlement().name())
                .param("tradingDay", state.tradingDay())
                .param("filledQuantity", state.filledQuantity().value())
                .param("filledValue", state.filledValue())
                .param("reason", state.rejectionReason())
                .param("acceptedAt", request.acceptedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Order order) {
        var state = order.state();
        jdbc.sql("""
                update investment_order set status = :status, settlement = :settlement, trading_day = :tradingDay,
                    filled_quantity = :filledQuantity, filled_value = :filledValue, rejection_reason = :reason
                where order_id = :id""")
                .param("id", order.id())
                .param("status", state.status().name())
                .param("settlement", state.settlement().name())
                .param("tradingDay", state.tradingDay())
                .param("filledQuantity", state.filledQuantity().value())
                .param("filledValue", state.filledValue())
                .param("reason", state.rejectionReason())
                .update();
    }

    @Override
    public Optional<Fill> findFill(String orderId, String execId) {
        return jdbc.sql("select * from order_fill where order_id = :order and exec_id = :exec")
                .param("order", orderId).param("exec", execId)
                .query(JdbcOrders::mapFill).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addFill(String orderId, Fill fill) {
        jdbc.sql("""
                insert into order_fill (order_id, exec_id, quantity, price, amount, realized_gain, traded_at)
                values (:order, :exec, :quantity, :price, :amount, :gain, :tradedAt)""")
                .param("order", orderId)
                .param("exec", fill.execId())
                .param("quantity", fill.quantity().value())
                .param("price", fill.price().value())
                .param("amount", fill.amount())
                .param("gain", fill.realizedGain())
                .param("tradedAt", fill.tradedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public List<Fill> fills(String orderId) {
        return jdbc.sql("select * from order_fill where order_id = :order order by seq").param("order", orderId)
                .query(JdbcOrders::mapFill).list();
    }

    @Override
    public long countTradingSince(LocalDate tradingDayBefore) {
        return jdbc.sql("""
                select count(*) from investment_order
                where status in ('PLACED', 'PARTIALLY_FILLED') and trading_day < :day""")
                .param("day", tradingDayBefore)
                .query(Long.class).single();
    }

    private static Order map(ResultSet row, int n) throws SQLException {
        var amount = row.getBigDecimal("amount");
        var quantity = row.getBigDecimal("quantity");
        var request = new Order.Request(row.getString("order_id"), row.getObject("portfolio_id", UUID.class),
                row.getString("isin").trim(), Order.Side.valueOf(row.getString("side")),
                amount == null ? null : new Money(amount), quantity == null ? null : new Quantity(quantity),
                row.getTimestamp("accepted_at").toInstant());
        return Order.restore(new Order.State(request, Order.Status.valueOf(row.getString("status")),
                Order.Settlement.valueOf(row.getString("settlement")), row.getObject("trading_day", LocalDate.class),
                new Quantity(row.getBigDecimal("filled_quantity")), row.getBigDecimal("filled_value"),
                row.getString("rejection_reason")));
    }

    private static Fill mapFill(ResultSet row, int n) throws SQLException {
        return new Fill(row.getString("exec_id"), new Quantity(row.getBigDecimal("quantity")),
                new Price(row.getBigDecimal("price")), row.getBigDecimal("amount"),
                row.getBigDecimal("realized_gain"), row.getTimestamp("traded_at").toInstant());
    }
}
