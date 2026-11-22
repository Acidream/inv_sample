package com.example.savings.adapter.persistence;

import com.example.savings.application.port.Deposits;
import com.example.savings.domain.Money;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcDeposits implements Deposits {

    private final JdbcClient jdbc;

    JdbcDeposits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert-or-nothing on the primary key. Two concurrent transactions with the same payment id
     * serialize on the key: the second waits for the first to commit, then inserts nothing.
     */
    @Override
    public Optional<RecordedDeposit> recordIfNew(RecordedDeposit deposit) {
        var inserted = jdbc.sql("""
                insert into deposit (payment_id, account_id, amount, event_id, received_at)
                values (:paymentId, :accountId, :amount, :eventId, :receivedAt)
                on conflict (payment_id) do nothing""")
                .param("paymentId", deposit.paymentId())
                .param("accountId", deposit.accountId())
                .param("amount", deposit.amount().amount())
                .param("eventId", deposit.eventId())
                .param("receivedAt", OffsetDateTime.ofInstant(deposit.receivedAt(), ZoneOffset.UTC))
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql("""
                select payment_id, account_id, amount, event_id, received_at
                from deposit where payment_id = :paymentId""")
                .param("paymentId", deposit.paymentId())
                .query((rs, row) -> new RecordedDeposit(
                        rs.getString("payment_id"),
                        rs.getObject("account_id", UUID.class),
                        new Money(rs.getBigDecimal("amount")),
                        rs.getString("event_id"),
                        rs.getObject("received_at", OffsetDateTime.class).toInstant()))
                .single());
    }
}
