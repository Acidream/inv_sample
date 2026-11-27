package com.example.savings.adapter.metrics;

import com.example.savings.application.port.SavingsMetrics;
import com.example.savings.domain.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Metric amounts are doubles: fine for dashboards, never used for bookkeeping.
 *
 * <p>Every counter is registered at start-up, so Prometheus scrapes it at 0 before the first event.
 * A counter that first appears already at 250 makes {@code increase()} and {@code rate()} read 0.
 */
@Component
class MicrometerSavingsMetrics implements SavingsMetrics {

    private static final List<String> WITHDRAWAL_OUTCOMES = List.of("withdrawn", "rejected", "returned");

    private final MeterRegistry registry;
    private final Counter deposits;
    private final Counter depositedEuros;
    private final Counter interestPaidEuros;
    private final Counter duplicateMessages;

    MicrometerSavingsMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.deposits = registry.counter("savings.deposits");
        this.depositedEuros = euros("savings.deposits.amount");
        this.interestPaidEuros = euros("savings.interest.paid");
        this.duplicateMessages = registry.counter("savings.messages.duplicate");
        WITHDRAWAL_OUTCOMES.forEach(this::withdrawals);
    }

    @Override
    public void deposited(Money amount) {
        deposits.increment();
        depositedEuros.increment(amount.amount().doubleValue());
    }

    @Override
    public void withdrawal(String outcome) {
        withdrawals(outcome).increment();
    }

    @Override
    public void interestPaid(Money amount) {
        interestPaidEuros.increment(amount.amount().doubleValue());
    }

    @Override
    public void duplicateMessage() {
        duplicateMessages.increment();
    }

    private Counter withdrawals(String outcome) {
        return registry.counter("savings.withdrawals", "outcome", outcome);
    }

    private Counter euros(String name) {
        return Counter.builder(name).baseUnit("euros").register(registry);
    }
}
