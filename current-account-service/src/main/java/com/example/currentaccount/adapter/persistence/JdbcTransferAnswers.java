package com.example.currentaccount.adapter.persistence;

import com.example.currentaccount.application.port.TransferAnswers;
import com.example.currentaccount.domain.Money;
import com.example.currentaccount.domain.TransferAnswer;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcTransferAnswers implements TransferAnswers {

    private final JdbcClient jdbc;

    JdbcTransferAnswers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TransferAnswer> find(UUID accountId, String transferId) {
        return jdbc.sql("""
                select account_id, transfer_id, direction, amount, rejection_reason, answered_at from transfer_answer
                where account_id = :account and transfer_id = :transfer""")
                .param("account", accountId)
                .param("transfer", transferId)
                .query((row, n) -> new TransferAnswer(row.getObject("account_id", UUID.class),
                        row.getString("transfer_id"), TransferAnswer.Direction.valueOf(row.getString("direction")),
                        new Money(row.getBigDecimal("amount")), row.getString("rejection_reason"),
                        row.getTimestamp("answered_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(TransferAnswer answer) {
        jdbc.sql("""
                insert into transfer_answer (account_id, transfer_id, direction, amount, rejection_reason, answered_at)
                values (:account, :transfer, :direction, :amount, :reason, :answeredAt)""")
                .param("account", answer.accountId())
                .param("transfer", answer.transferId())
                .param("direction", answer.direction().name())
                .param("amount", answer.amount().amount())
                .param("reason", answer.rejectionReason())
                .param("answeredAt", answer.answeredAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
