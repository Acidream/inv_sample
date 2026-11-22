package com.example.savings.adapter.persistence;

import com.example.savings.application.port.InterestAccruals;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcInterestAccruals implements InterestAccruals {

    private final JdbcClient jdbc;

    JdbcInterestAccruals(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID accountId, LocalDate day, BigDecimal amount) {
        jdbc.sql("insert into interest_accrual (account_id, accrual_date, amount) values (:account, :day, :amount)")
                .param("account", accountId)
                .param("day", day)
                .param("amount", amount)
                .update();
    }
}
