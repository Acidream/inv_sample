package com.example.transfers.adapter.metrics;

import com.example.transfers.application.TransferService;
import com.example.transfers.config.TransferProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * {@code transfers.stuck}: transfers that have waited for a ledger's answer longer than
 * {@code transfers.stuck-after} (ADR 0023). A ledger answers within seconds, so anything above zero
 * means an instruction or an answer was dead-lettered, or a bug. The {@code TransferStuck} alert pages.
 */
@Component
class StuckTransfersGauge {

    private final TransferService transfers;
    private final Clock clock;
    private final Duration stuckAfter;

    StuckTransfersGauge(TransferService transfers, Clock clock, TransferProperties properties, MeterRegistry registry) {
        this.transfers = transfers;
        this.clock = clock;
        this.stuckAfter = properties.stuckAfter();
        Gauge.builder("transfers.stuck", this, StuckTransfersGauge::count)
                .description("Transfers waiting for a ledger's answer longer than transfers.stuck-after")
                .register(registry);
    }

    double count() {
        return transfers.countStuck(clock.instant().minus(stuckAfter));
    }
}
