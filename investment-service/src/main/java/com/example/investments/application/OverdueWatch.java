package com.example.investments.application;

import com.example.investments.application.port.Orders;
import com.example.investments.application.port.Payouts;
import com.example.investments.domain.TradingCalendar;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What the alerts count (ADR 0026, 0027): customer money in flight for longer than it should be. */
@Service
public class OverdueWatch {

    private final Orders orders;
    private final Payouts payouts;

    OverdueWatch(Orders orders, Payouts payouts) {
        this.orders = orders;
        this.payouts = payouts;
    }

    /** Orders still with the broker after their trading day ended (ADR 0027's alert). */
    @Transactional(readOnly = true)
    public long countOverdueOrders(Instant now) {
        return orders.countTradingSince(TradingCalendar.businessDayOf(now));
    }

    @Transactional(readOnly = true)
    public long countUnpaidPayoutsSince(Instant before) {
        return payouts.countUnpaidSince(before);
    }
}
