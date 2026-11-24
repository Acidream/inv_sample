package com.example.investments.adapter.persistence;

import com.example.investments.application.port.Holdings;
import com.example.investments.domain.Holding;
import com.example.investments.domain.Lot;
import com.example.investments.domain.Price;
import com.example.investments.domain.Quantity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcHoldings implements Holdings {

    private record Row(UUID portfolioId, String isin, Quantity reserved, Price lastPrice) {
    }

    private final JdbcClient jdbc;

    JdbcHoldings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Holding get(UUID portfolioId, String isin) {
        return jdbc.sql("select * from holding where portfolio_id = :portfolio and isin = :isin")
                .param("portfolio", portfolioId).param("isin", isin)
                .query(JdbcHoldings::mapRow).optional()
                .map(this::withLots)
                .orElseGet(() -> Holding.empty(portfolioId, isin));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Holding holding) {
        jdbc.sql("""
                insert into holding (portfolio_id, isin, reserved, last_price)
                values (:portfolio, :isin, :reserved, :price)
                on conflict (portfolio_id, isin)
                do update set reserved = excluded.reserved, last_price = excluded.last_price""")
                .param("portfolio", holding.portfolioId())
                .param("isin", holding.isin())
                .param("reserved", holding.reserved().value())
                .param("price", holding.lastPrice().map(Price::value).orElse(null))
                .update();
        for (var lot : holding.lots()) {
            if (lot.id() == null) {
                jdbc.sql("""
                        insert into lot (portfolio_id, isin, order_id, exec_id, quantity, remaining_quantity,
                            remaining_cost, acquired_at)
                        values (:portfolio, :isin, :order, :exec, :quantity, :remaining, :cost, :acquiredAt)""")
                        .param("portfolio", holding.portfolioId())
                        .param("isin", holding.isin())
                        .param("order", lot.orderId())
                        .param("exec", lot.execId())
                        .param("quantity", lot.quantity().value())
                        .param("remaining", lot.remaining().value())
                        .param("cost", lot.remainingCost())
                        .param("acquiredAt", lot.acquiredAt().atOffset(ZoneOffset.UTC))
                        .update();
            } else {
                jdbc.sql("update lot set remaining_quantity = :remaining, remaining_cost = :cost where id = :id")
                        .param("id", lot.id())
                        .param("remaining", lot.remaining().value())
                        .param("cost", lot.remainingCost())
                        .update();
            }
        }
    }

    @Override
    public List<Holding> of(UUID portfolioId) {
        return jdbc.sql("select * from holding where portfolio_id = :portfolio order by isin")
                .param("portfolio", portfolioId)
                .query(JdbcHoldings::mapRow).list().stream()
                .map(this::withLots)
                .filter(holding -> holding.quantity().isPositive() || holding.reserved().isPositive())
                .toList();
    }

    private Holding withLots(Row row) {
        var lots = jdbc.sql("""
                select * from lot where portfolio_id = :portfolio and isin = :isin and remaining_quantity > 0
                order by id""")
                .param("portfolio", row.portfolioId()).param("isin", row.isin())
                .query(JdbcHoldings::mapLot).list();
        return Holding.restore(row.portfolioId(), row.isin(), lots, row.reserved(), row.lastPrice());
    }

    private static Row mapRow(ResultSet row, int n) throws SQLException {
        var price = row.getBigDecimal("last_price");
        return new Row(row.getObject("portfolio_id", UUID.class), row.getString("isin"),
                new Quantity(row.getBigDecimal("reserved")), price == null ? null : new Price(price));
    }

    private static Lot mapLot(ResultSet row, int n) throws SQLException {
        return new Lot(row.getLong("id"), row.getString("order_id"), row.getString("exec_id"),
                new Quantity(row.getBigDecimal("quantity")), new Quantity(row.getBigDecimal("remaining_quantity")),
                row.getBigDecimal("remaining_cost"), row.getTimestamp("acquired_at").toInstant());
    }
}
