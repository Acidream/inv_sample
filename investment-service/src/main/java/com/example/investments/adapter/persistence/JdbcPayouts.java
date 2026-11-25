package com.example.investments.adapter.persistence;

import com.example.investments.application.port.Payouts;
import com.example.investments.domain.Money;
import com.example.investments.domain.Payout;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcPayouts implements Payouts {

    private final JdbcClient jdbc;

    JdbcPayouts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Payout> find(UUID payoutId) {
        return jdbc.sql("select * from payout where payout_id = :id").param("id", payoutId)
                .query(JdbcPayouts::map).optional();
    }

    @Override
    public Optional<Payout> findByTransfer(String transferId) {
        return jdbc.sql("select * from payout where transfer_id = :id").param("id", transferId)
                .query(JdbcPayouts::map).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(Payout payout) {
        jdbc.sql("""
                insert into payout (payout_id, portfolio_id, order_id, reason, amount, status, attempt, transfer_id,
                    unpaid_since, created_at)
                values (:id, :portfolio, :order, :reason, :amount, :status, :attempt, :transfer, :unpaidSince,
                    :createdAt)""")
                .param("id", payout.id())
                .param("portfolio", payout.portfolioId())
                .param("order", payout.orderId().orElse(null))
                .param("reason", payout.reason().name())
                .param("amount", payout.amount().amount())
                .param("status", payout.status().name())
                .param("attempt", payout.attempt())
                .param("transfer", payout.transferId())
                .param("unpaidSince", payout.unpaidSince().map(at -> at.atOffset(ZoneOffset.UTC)).orElse(null))
                .param("createdAt", payout.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Payout payout) {
        jdbc.sql("""
                update payout set status = :status, attempt = :attempt, transfer_id = :transfer,
                    unpaid_since = :unpaidSince
                where payout_id = :id""")
                .param("id", payout.id())
                .param("status", payout.status().name())
                .param("attempt", payout.attempt())
                .param("transfer", payout.transferId())
                .param("unpaidSince", payout.unpaidSince().map(at -> at.atOffset(ZoneOffset.UTC)).orElse(null))
                .update();
    }

    @Override
    public List<Payout> unpaid(UUID portfolioId) {
        return jdbc.sql("select * from payout where portfolio_id = :id and status = 'UNPAID' order by unpaid_since")
                .param("id", portfolioId)
                .query(JdbcPayouts::map).list();
    }

    @Override
    public List<UUID> unpaidIdsTo(UUID referenceAccountId) {
        return jdbc.sql("""
                select p.payout_id from payout p join portfolio f on f.portfolio_id = p.portfolio_id
                where p.status = 'UNPAID' and f.reference_account_id = :account
                order by p.unpaid_since, p.payout_id""")
                .param("account", referenceAccountId)
                .query(UUID.class).list();
    }

    @Override
    public List<UUID> oldestUnpaidIds(int limit) {
        return jdbc.sql("""
                select payout_id from payout where status = 'UNPAID'
                order by unpaid_since, payout_id
                limit :limit""")
                .param("limit", limit)
                .query(UUID.class).list();
    }

    @Override
    public long countUnpaidSince(Instant before) {
        return jdbc.sql("select count(*) from payout where status = 'UNPAID' and unpaid_since < :before")
                .param("before", before.atOffset(ZoneOffset.UTC))
                .query(Long.class).single();
    }

    private static Payout map(ResultSet row, int n) throws SQLException {
        Timestamp unpaidSince = row.getTimestamp("unpaid_since");
        return Payout.restore(row.getObject("payout_id", UUID.class), row.getObject("portfolio_id", UUID.class),
                row.getString("order_id"), Payout.Reason.valueOf(row.getString("reason")),
                new Money(row.getBigDecimal("amount")), row.getTimestamp("created_at").toInstant(),
                Payout.Status.valueOf(row.getString("status")), row.getInt("attempt"),
                unpaidSince == null ? null : unpaidSince.toInstant());
    }
}
