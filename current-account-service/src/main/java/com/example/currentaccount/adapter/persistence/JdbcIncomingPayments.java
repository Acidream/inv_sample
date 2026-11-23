package com.example.currentaccount.adapter.persistence;

import com.example.currentaccount.application.port.IncomingPayments;
import com.example.currentaccount.domain.IncomingPayment;
import com.example.currentaccount.domain.Money;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcIncomingPayments implements IncomingPayments {

    private final JdbcClient jdbc;

    JdbcIncomingPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IncomingPayment> find(String paymentId) {
        return jdbc.sql("""
                select payment_id, account_id, amount, status, rejection_reason, received_at from incoming_payment
                where payment_id = :id""")
                .param("id", paymentId)
                .query((row, n) -> new IncomingPayment(row.getString("payment_id"),
                        row.getObject("account_id", UUID.class), new Money(row.getBigDecimal("amount")),
                        IncomingPayment.Status.valueOf(row.getString("status")), row.getString("rejection_reason"),
                        row.getTimestamp("received_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(IncomingPayment payment) {
        jdbc.sql("""
                insert into incoming_payment (payment_id, account_id, amount, status, rejection_reason, received_at)
                values (:id, :account, :amount, :status, :reason, :receivedAt)""")
                .param("id", payment.paymentId())
                .param("account", payment.accountId())
                .param("amount", payment.amount().amount())
                .param("status", payment.status().name())
                .param("reason", payment.rejectionReason())
                .param("receivedAt", payment.receivedAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
