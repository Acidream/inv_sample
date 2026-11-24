package com.example.fixgateway.adapter.persistence;

import com.example.fixgateway.application.port.Executions;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcExecutions implements Executions {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcExecutions(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markReported(String orderId, String execId) {
        return jdbc.sql("""
                insert into broker_execution (order_id, exec_id, reported_at) values (:order, :exec, :at)
                on conflict do nothing""")
                .param("order", orderId)
                .param("exec", execId)
                .param("at", clock.instant().atOffset(ZoneOffset.UTC))
                .update() == 1;
    }
}
