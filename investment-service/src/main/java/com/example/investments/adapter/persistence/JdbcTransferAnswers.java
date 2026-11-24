package com.example.investments.adapter.persistence;

import com.example.investments.application.port.TransferAnswers;
import com.example.investments.domain.Money;
import com.example.investments.domain.TransferAnswer;
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
class JdbcTransferAnswers implements TransferAnswers {

    private final JdbcClient jdbc;

    JdbcTransferAnswers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TransferAnswer> find(UUID portfolioId, String transferId, TransferAnswer.Kind kind) {
        return jdbc.sql("""
                select * from transfer_answer
                where portfolio_id = :portfolio and transfer_id = :transfer and kind = :kind""")
                .param("portfolio", portfolioId).param("transfer", transferId).param("kind", kind.name())
                .query(JdbcTransferAnswers::map).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(TransferAnswer answer) {
        jdbc.sql("""
                insert into transfer_answer (portfolio_id, transfer_id, kind, amount, rejection_reason, answered_at)
                values (:portfolio, :transfer, :kind, :amount, :reason, :answeredAt)""")
                .param("portfolio", answer.portfolioId())
                .param("transfer", answer.transferId())
                .param("kind", answer.kind().name())
                .param("amount", answer.amount().amount())
                .param("reason", answer.rejectionReason())
                .param("answeredAt", answer.answeredAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    private static TransferAnswer map(ResultSet row, int n) throws SQLException {
        return new TransferAnswer(row.getObject("portfolio_id", UUID.class), row.getString("transfer_id"),
                TransferAnswer.Kind.valueOf(row.getString("kind")), new Money(row.getBigDecimal("amount")),
                row.getString("rejection_reason"), row.getTimestamp("answered_at").toInstant());
    }
}
