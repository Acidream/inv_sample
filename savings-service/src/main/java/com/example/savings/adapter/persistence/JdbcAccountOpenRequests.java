package com.example.savings.adapter.persistence;

import com.example.savings.application.IdempotencyKeyInUseException;
import com.example.savings.application.port.AccountOpenRequests;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcAccountOpenRequests implements AccountOpenRequests {

    private final JdbcClient jdbc;

    JdbcAccountOpenRequests(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> find(String idempotencyKey) {
        return jdbc.sql("select account_id from account_open_request where idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(UUID.class)
                .optional();
    }

    /** A concurrent request with the same key blocks on the primary key until the first one commits, then fails. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String idempotencyKey, UUID accountId) {
        try {
            jdbc.sql("insert into account_open_request (idempotency_key, account_id) values (:key, :account)")
                    .param("key", idempotencyKey)
                    .param("account", accountId)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new IdempotencyKeyInUseException(idempotencyKey);
        }
    }
}
