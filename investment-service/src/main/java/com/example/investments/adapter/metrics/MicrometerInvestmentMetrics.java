package com.example.investments.adapter.metrics;

import com.example.investments.application.port.InvestmentMetrics;
import com.example.investments.domain.Money;
import com.example.investments.domain.Order;
import com.example.investments.domain.Payout;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Business counters (ADR 0013): orders by side and outcome, fills and their volume, payouts and
 * refused payouts. Metric amounts are doubles: fine for dashboards, never used for bookkeeping.
 */
@Component
class MicrometerInvestmentMetrics implements InvestmentMetrics {

    private final MeterRegistry registry;

    MicrometerInvestmentMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Registered at 0, so an increase() over them works from the first event (ADR 0013).
        for (var reason : Payout.Reason.values()) {
            payoutsRefused(reason);
        }
    }

    @Override
    public void orderReceived(Order order) {
        registry.counter("investments.orders.received", "side", tag(order.side()), "status", tag(order.status()))
                .increment();
    }

    @Override
    public void filled(Order.Side side, BigDecimal amount) {
        registry.counter("investments.fills", "side", tag(side)).increment();
        euros("investments.fills.amount", "side", tag(side)).increment(amount.doubleValue());
    }

    @Override
    public void orderEnded(Order order) {
        registry.counter("investments.orders.ended", "side", tag(order.side()), "status", tag(order.status()))
                .increment();
    }

    @Override
    public void payoutRequested(Payout.Reason reason, Money amount) {
        registry.counter("investments.payouts.requested", "reason", tag(reason)).increment();
        euros("investments.payouts.requested.amount", "reason", tag(reason)).increment(amount.amount().doubleValue());
    }

    @Override
    public void payoutUnpaid(Payout.Reason reason) {
        payoutsRefused(reason).increment();
    }

    @Override
    public void duplicateMessage() {
        registry.counter("investments.messages.duplicate").increment();
    }

    private Counter payoutsRefused(Payout.Reason reason) {
        return registry.counter("investments.payouts.refused", "reason", tag(reason));
    }

    private Counter euros(String name, String tag, String value) {
        return Counter.builder(name).baseUnit("euros").tag(tag, value).register(registry);
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
