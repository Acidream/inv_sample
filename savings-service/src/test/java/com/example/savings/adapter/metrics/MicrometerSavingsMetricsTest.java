package com.example.savings.adapter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.savings.domain.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerSavingsMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerSavingsMetrics metrics = new MicrometerSavingsMetrics(registry);

    @Test
    void constructor_NothingHappenedYet_EveryCounterExistsAtZero() {
        // Prometheus must scrape 0 before the first event, or increase() hides that event.
        assertThat(registry.get("savings.deposits").counter().count()).isZero();
        assertThat(registry.get("savings.deposits.amount").counter().count()).isZero();
        assertThat(registry.get("savings.interest.paid").counter().count()).isZero();
        assertThat(registry.get("savings.messages.duplicate").counter().count()).isZero();
        for (var outcome : new String[] {"withdrawn", "rejected", "returned"}) {
            assertThat(registry.get("savings.withdrawals").tag("outcome", outcome).counter().count()).isZero();
        }
    }

    @Test
    void deposited_TwoHundredFiftyEuros_CountsOneDepositOfThatAmount() {
        metrics.deposited(Money.of("250.00"));

        assertThat(registry.get("savings.deposits").counter().count()).isEqualTo(1);
        assertThat(registry.get("savings.deposits.amount").counter().count()).isEqualTo(250);
    }

    @Test
    void withdrawal_Returned_CountsOnlyThatOutcome() {
        metrics.withdrawal("returned");

        assertThat(registry.get("savings.withdrawals").tag("outcome", "returned").counter().count()).isEqualTo(1);
        assertThat(registry.get("savings.withdrawals").tag("outcome", "withdrawn").counter().count()).isZero();
    }
}
