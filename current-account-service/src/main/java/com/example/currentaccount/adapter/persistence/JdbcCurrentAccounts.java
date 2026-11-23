package com.example.currentaccount.adapter.persistence;

import com.example.currentaccount.application.port.CurrentAccounts;
import com.example.currentaccount.domain.CurrentAccount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcCurrentAccounts implements CurrentAccounts {

    private static final String SELECT = """
            select account_id, customer_id, overdraft_limit, balance, status, block_reason, opened_at
            from current_account where account_id = :id""";

    private final JdbcClient jdbc;

    JdbcCurrentAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CurrentAccount> find(UUID accountId) {
        return jdbc.sql(SELECT).param("id", accountId).query(JdbcCurrentAccounts::map).optional();
    }

    /** A row lock: commands and instructions for one account wait for each other. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CurrentAccount> lock(UUID accountId) {
        return jdbc.sql(SELECT + " for update").param("id", accountId).query(JdbcCurrentAccounts::map).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(CurrentAccount account) {
        jdbc.sql("""
                insert into current_account
                    (account_id, customer_id, overdraft_limit, balance, status, block_reason, opened_at)
                values (:id, :customer, :limit, :balance, :status, :reason, :openedAt)""")
                .param("id", account.id())
                .param("customer", account.customerId())
                .param("limit", account.overdraftLimit())
                .param("balance", account.balance())
                .param("status", account.status().name())
                .param("reason", account.blockReason().orElse(null))
                .param("openedAt", account.openedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(CurrentAccount account) {
        jdbc.sql("""
                update current_account set balance = :balance, status = :status, block_reason = :reason
                where account_id = :id""")
                .param("id", account.id())
                .param("balance", account.balance())
                .param("status", account.status().name())
                .param("reason", account.blockReason().orElse(null))
                .update();
    }

    private static CurrentAccount map(ResultSet row, int n) throws SQLException {
        return CurrentAccount.restore(row.getObject("account_id", UUID.class),
                row.getObject("customer_id", UUID.class), row.getBigDecimal("overdraft_limit"),
                row.getTimestamp("opened_at").toInstant(), row.getBigDecimal("balance"),
                CurrentAccount.Status.valueOf(row.getString("status")), row.getString("block_reason"));
    }
}
