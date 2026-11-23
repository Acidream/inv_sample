package com.example.transfers.adapter.persistence;

import com.example.transfers.application.port.Transfers;
import com.example.transfers.domain.Money;
import com.example.transfers.domain.Transfer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link Transfers} on the {@code transfer} table; updates are guarded by {@code version}. Also keeps
 * two times the domain doesn't need (ADR 0023): {@code received_at}, when the request was applied,
 * and {@code status_changed_at}, when the status last changed. Both come from the service's
 * {@link Clock}, like the times they are compared with.
 */
@Component
class JdbcTransfers implements Transfers {

    private static final String IN_FLIGHT = "('DEBITING', 'CREDITING', 'RETURNING')";

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcTransfers(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(Transfer transfer) {
        var state = transfer.state();
        var request = state.request();
        jdbc.sql("""
                insert into transfer (transfer_id, customer_id, from_account_id, to_account_id, amount, requested_at,
                    status, route, failure_reason, failure_detail, version, received_at, status_changed_at)
                values (:transferId, :customerId, :from, :to, :amount, :requestedAt, :status, :route, :reason,
                    :detail, 0, :now, :now)
                """)
                .param("transferId", request.transferId())
                .param("customerId", request.customerId())
                .param("from", request.fromAccountId())
                .param("to", request.toAccountId())
                .param("amount", request.amount().amount())
                .param("requestedAt", request.requestedAt().atOffset(ZoneOffset.UTC))
                .param("status", state.status().name())
                .param("route", name(state.route()))
                .param("reason", name(state.failureReason()))
                .param("detail", state.failureDetail())
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<Transfer> find(String transferId) {
        return jdbc.sql("select * from transfer where transfer_id = :id").param("id", transferId)
                .query(JdbcTransfers::map).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Transfer transfer) {
        var state = transfer.state();
        int updated = jdbc.sql("""
                update transfer set status = :status, route = :route, failure_reason = :reason,
                    failure_detail = :detail, version = version + 1,
                    status_changed_at = case when status = :status then status_changed_at else :now end
                where transfer_id = :transferId and version = :version""")
                .param("status", state.status().name())
                .param("route", name(state.route()))
                .param("reason", name(state.failureReason()))
                .param("detail", state.failureDetail())
                .param("transferId", state.request().transferId())
                .param("version", state.version())
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Transfer " + state.request().transferId()
                    + " was changed concurrently");
        }
    }

    @Override
    public List<Transfer> awaiting(UUID accountId) {
        return jdbc.sql("""
                select * from transfer
                where status = 'AWAITING_ACCOUNTS' and (from_account_id = :id or to_account_id = :id)
                order by requested_at, transfer_id
                for update""")
                .param("id", accountId)
                .query(JdbcTransfers::map).list();
    }

    @Override
    public List<Transfer> awaitingSince(Instant receivedBefore, int limit) {
        return jdbc.sql("""
                select * from transfer
                where status = 'AWAITING_ACCOUNTS' and received_at < :before
                order by received_at, transfer_id
                limit :limit
                for update skip locked""")
                .param("before", receivedBefore.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(JdbcTransfers::map).list();
    }

    @Override
    public long countInFlightSince(Instant changedBefore) {
        return jdbc.sql("select count(*) from transfer where status in " + IN_FLIGHT
                        + " and status_changed_at < :before")
                .param("before", changedBefore.atOffset(ZoneOffset.UTC))
                .query(Long.class).single();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Transfer map(ResultSet row, int rowNum) throws SQLException {
        var request = new Transfer.Request(row.getString("transfer_id"), row.getObject("customer_id", UUID.class),
                row.getObject("from_account_id", UUID.class), row.getObject("to_account_id", UUID.class),
                new Money(row.getBigDecimal("amount")), row.getTimestamp("requested_at").toInstant());
        var route = row.getString("route");
        var reason = row.getString("failure_reason");
        return Transfer.restore(new Transfer.State(request, Transfer.Status.valueOf(row.getString("status")),
                route == null ? null : Transfer.Route.valueOf(route),
                reason == null ? null : Transfer.FailureReason.valueOf(reason),
                row.getString("failure_detail"), row.getLong("version")));
    }
}
