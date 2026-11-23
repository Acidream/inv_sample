package com.example.currentaccount.adapter.persistence;

import com.example.currentaccount.application.port.Bookings;
import com.example.currentaccount.domain.Booking;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcBookings implements Bookings {

    private final JdbcClient jdbc;

    JdbcBookings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(Booking booking) {
        jdbc.sql("""
                insert into booking (account_id, reference, type, amount, balance_after, booked_at)
                values (:account, :reference, :type, :amount, :balanceAfter, :bookedAt)""")
                .param("account", booking.accountId())
                .param("reference", booking.reference())
                .param("type", booking.type().name())
                .param("amount", booking.amount())
                .param("balanceAfter", booking.balanceAfter())
                .param("bookedAt", booking.bookedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public List<Booking> latest(UUID accountId, int limit) {
        return jdbc.sql("""
                select account_id, reference, type, amount, balance_after, booked_at from booking
                where account_id = :account order by id desc limit :limit""")
                .param("account", accountId)
                .param("limit", limit)
                .query((row, n) -> new Booking(row.getObject("account_id", UUID.class), row.getString("reference"),
                        Booking.Type.valueOf(row.getString("type")), row.getBigDecimal("amount"),
                        row.getBigDecimal("balance_after"), row.getTimestamp("booked_at").toInstant()))
                .list();
    }
}
