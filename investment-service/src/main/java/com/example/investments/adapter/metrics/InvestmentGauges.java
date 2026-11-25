package com.example.investments.adapter.metrics;

import com.example.investments.application.OverdueWatch;
import com.example.investments.config.InvestmentProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Two gauges, each with an alert (ADR 0026, 0027):
 *
 * <ul>
 *   <li>{@code investments.orders.overdue}: orders still with the broker after their trading day
 *       ended (plus {@code investments.overdue-after}). The broker expires every order at the end of
 *       its day, so a lost report or a stuck gateway leaves the customer's money in flight.
 *   <li>{@code investments.payouts.unpaid.overdue}: payouts the current account has refused for
 *       longer than {@code investments.unpaid-alert-after}. Customer money resting on a portfolio.
 * </ul>
 */
@Component
class InvestmentGauges {

    InvestmentGauges(OverdueWatch watch, Clock clock, InvestmentProperties properties,
            MeterRegistry registry) {
        Gauge.builder("investments.orders.overdue",
                        () -> watch.countOverdueOrders(clock.instant().minus(properties.overdueAfter())))
                .description("Orders still with the broker after their trading day ended")
                .register(registry);
        Gauge.builder("investments.payouts.unpaid.overdue",
                        () -> watch.countUnpaidPayoutsSince(clock.instant().minus(properties.unpaidAlertAfter())))
                .description("Payouts unpaid for longer than investments.unpaid-alert-after")
                .register(registry);
    }
}
