package com.example.transfers.adapter.persistence;

import com.example.transfers.application.port.AccountDirectory;
import com.example.transfers.domain.KnownAccount;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcAccountDirectory implements AccountDirectory {

    private final JdbcClient jdbc;

    JdbcAccountDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Transaction-scoped advisory locks, taken in a fixed order so two transactions can't deadlock. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID... accountIds) {
        Arrays.stream(accountIds).distinct().sorted().forEach(id ->
                jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:id, 0))")
                        .param("id", id.toString())
                        .query((row, n) -> 1)
                        .single());
    }

    @Override
    public void add(KnownAccount account) {
        jdbc.sql("""
                insert into known_account (account_id, customer_id, ledger) values (:id, :customer, :ledger)
                on conflict (account_id) do nothing""")
                .param("id", account.accountId())
                .param("customer", account.customerId())
                .param("ledger", account.ledger().name())
                .update();
    }

    @Override
    public Optional<KnownAccount> find(UUID accountId) {
        return jdbc.sql("select account_id, customer_id, ledger from known_account where account_id = :id")
                .param("id", accountId)
                .query((row, n) -> new KnownAccount(row.getObject("account_id", UUID.class),
                        row.getObject("customer_id", UUID.class), KnownAccount.Ledger.valueOf(row.getString("ledger"))))
                .optional();
    }
}
