package com.example.savings.adapter.metrics;

import com.example.savings.application.port.SavingsMetrics;
import com.example.savings.domain.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Metric amounts are doubles: fine for dashboards, never used for bookkeeping. */
@Component
class MicrometerSavingsMetrics implements SavingsMetrics {

    private final MeterRegistry registry;

    MicrometerSavingsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void deposited(Money amount) {
        registry.counter("savings.deposits").increment();
        euros("savings.deposits.amount").increment(amount.amount().doubleValue());
    }

    @Override
    public void withdrawal(String outcome) {
        registry.counter("savings.withdrawals", "outcome", outcome).increment();
    }

    @Override
    public void interestPaid(Money amount) {
        euros("savings.interest.paid").increment(amount.amount().doubleValue());
    }

    @Override
    public void duplicateMessage() {
        registry.counter("savings.messages.duplicate").increment();
    }

    private Counter euros(String name) {
        return Counter.builder(name).baseUnit("euros").register(registry);
    }
}
