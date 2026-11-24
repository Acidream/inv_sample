package com.example.investments.adapter.persistence;

import com.example.investments.application.port.Portfolios;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.Price;
import com.example.investments.domain.Quantity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcPortfolios implements Portfolios {

    private static final String SELECT = """
            select portfolio_id, customer_id, reference_account_id, cash, last_entry_no, opened_at
            from portfolio where portfolio_id = :id""";

    private final JdbcClient jdbc;

    JdbcPortfolios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Portfolio> find(UUID portfolioId) {
        return jdbc.sql(SELECT).param("id", portfolioId).query(JdbcPortfolios::map).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Portfolio> lock(UUID portfolioId) {
        return jdbc.sql(SELECT + " for update").param("id", portfolioId).query(JdbcPortfolios::map).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(Portfolio portfolio) {
        jdbc.sql("""
                insert into portfolio (portfolio_id, customer_id, reference_account_id, cash, last_entry_no, opened_at)
                values (:id, :customer, :reference, :cash, :lastEntryNo, :openedAt)""")
                .param("id", portfolio.id())
                .param("customer", portfolio.customerId())
                .param("reference", portfolio.referenceAccountId())
                .param("cash", portfolio.cash())
                .param("lastEntryNo", portfolio.lastEntryNo())
                .param("openedAt", portfolio.openedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Portfolio portfolio, List<CashEntry> newEntries) {
        jdbc.sql("update portfolio set cash = :cash, last_entry_no = :lastEntryNo where portfolio_id = :id")
                .param("id", portfolio.id())
                .param("cash", portfolio.cash())
                .param("lastEntryNo", portfolio.lastEntryNo())
                .update();
        for (var entry : newEntries) {
            var trade = entry.tradeDetails();
            jdbc.sql("""
                    insert into cash_entry (portfolio_id, entry_no, type, amount, balance_after, reference, order_id,
                        isin, quantity, price, realized_gain, booked_at)
                    values (:portfolio, :entryNo, :type, :amount, :balanceAfter, :reference, :orderId, :isin, :quantity,
                        :price, :gain, :bookedAt)""")
                    .param("portfolio", entry.portfolioId())
                    .param("entryNo", entry.entryNo())
                    .param("type", entry.type().name())
                    .param("amount", entry.amount())
                    .param("balanceAfter", entry.balanceAfter())
                    .param("reference", entry.reference())
                    .param("orderId", entry.orderId())
                    .param("isin", trade.map(CashEntry.Trade::isin).orElse(null))
                    .param("quantity", trade.map(t -> t.quantity().value()).orElse(null))
                    .param("price", trade.map(t -> t.price().value()).orElse(null))
                    .param("gain", trade.map(CashEntry.Trade::realizedGain).orElse(null))
                    .param("bookedAt", entry.bookedAt().atOffset(ZoneOffset.UTC))
                    .update();
        }
    }

    @Override
    public List<CashEntry> entries(UUID portfolioId, Long beforeEntryNo, int limit) {
        return jdbc.sql("""
                select * from cash_entry
                where portfolio_id = :id and (cast(:before as bigint) is null or entry_no < :before)
                order by entry_no desc
                limit :limit""")
                .param("id", portfolioId)
                .param("before", beforeEntryNo)
                .param("limit", limit)
                .query(JdbcPortfolios::mapEntry).list();
    }

    private static Portfolio map(ResultSet row, int n) throws SQLException {
        return Portfolio.restore(row.getObject("portfolio_id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getObject("reference_account_id", UUID.class), row.getTimestamp("opened_at").toInstant(),
                row.getBigDecimal("cash"), row.getLong("last_entry_no"));
    }

    private static CashEntry mapEntry(ResultSet row, int n) throws SQLException {
        var isin = row.getString("isin");
        var trade = isin == null ? null : new CashEntry.Trade(isin, new Quantity(row.getBigDecimal("quantity")),
                new Price(row.getBigDecimal("price")), row.getBigDecimal("realized_gain"));
        return new CashEntry(row.getObject("portfolio_id", UUID.class), row.getLong("entry_no"),
                CashEntry.Type.valueOf(row.getString("type")), row.getBigDecimal("amount"),
                row.getBigDecimal("balance_after"), row.getString("reference"), row.getString("order_id"), trade,
                row.getTimestamp("booked_at").toInstant());
    }
}
