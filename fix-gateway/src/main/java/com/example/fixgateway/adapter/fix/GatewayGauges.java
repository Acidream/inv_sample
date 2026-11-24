package com.example.fixgateway.adapter.fix;

import com.example.fixgateway.application.GatewayService;
import com.example.fixgateway.config.GatewayProperties;
import com.example.fixgateway.domain.BrokerOrder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Gauges for the alerts of ADR 0027:
 *
 * <ul>
 *   <li>{@code fix.session.held}: 1 on the instance holding the FIX session. Summed over instances,
 *       0 means nobody can trade.
 *   <li>{@code fix.session.logged.on}: 1 while that session is logged on. 0 for more than five minutes
 *       on a trading day pages: orders wait, and their day may end before they go out.
 *   <li>{@code gateway.orders.overdue}: orders the broker has not ended a while after their trading day
 *       ended. Every market order expires at the end of its day, so a missing report is the cause.
 * </ul>
 */
@Component
class GatewayGauges {

    GatewayGauges(FixSession session, GatewayService gateway, Clock clock, GatewayProperties properties,
            MeterRegistry registry) {
        Gauge.builder("fix.session.held", () -> session.isHeld() ? 1 : 0)
                .description("1 on the instance holding the FIX session")
                .register(registry);
        Gauge.builder("fix.session.logged.on", () -> session.isLoggedOn() ? 1 : 0)
                .description("1 while the FIX session to the broker is logged on")
                .register(registry);
        Gauge.builder("gateway.orders.overdue", () -> gateway.countOverdue(
                        clock.instant().minus(properties.overdueAfter()).atZone(BrokerOrder.ZONE).toLocalDate()))
                .description("Orders still with the broker after their trading day ended")
                .register(registry);
    }
}
