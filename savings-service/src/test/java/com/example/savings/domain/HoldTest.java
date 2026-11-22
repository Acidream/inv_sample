package com.example.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.savings.domain.Hold.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldTest {

    private static final Instant T0 = Instant.parse("2026-03-02T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(72);

    private final Hold hold = Hold.reserve(UUID.randomUUID(), "wd-1", Money.of("30.00"), T0, TTL);

    @Test
    void confirmSettles() {
        assertThat(hold.confirm(T0)).isEqualTo(Outcome.APPLIED);
        assertThat(hold.status()).isEqualTo(HoldStatus.SETTLED);
        assertThat(hold.confirm(T0)).as("redelivery").isEqualTo(Outcome.NO_OP);
    }

    @Test
    void cancelReleases() {
        assertThat(hold.cancel(T0)).isEqualTo(Outcome.APPLIED);
        assertThat(hold.status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(hold.cancel(T0)).as("redelivery").isEqualTo(Outcome.NO_OP);
    }

    @Test
    void expiresExactlyAtTheDeadline() {
        assertThat(hold.expire(T0.plus(TTL).minusSeconds(1))).isEqualTo(Outcome.NO_OP);
        assertThat(hold.status()).isEqualTo(HoldStatus.RESERVED);

        assertThat(hold.expire(T0.plus(TTL))).isEqualTo(Outcome.APPLIED);
        assertThat(hold.status()).isEqualTo(HoldStatus.EXPIRED);
    }

    @Test
    void lateConfirmationOfExpiredHoldStillSettles() {
        hold.expire(T0.plus(TTL));

        assertThat(hold.confirm(T0.plus(TTL).plusSeconds(60))).isEqualTo(Outcome.APPLIED_AFTER_EXPIRY);
        assertThat(hold.status()).isEqualTo(HoldStatus.SETTLED);
    }

    @Test
    void cancellingAnExpiredHoldIsHarmless() {
        hold.expire(T0.plus(TTL));

        assertThat(hold.cancel(T0.plus(TTL))).isEqualTo(Outcome.NO_OP);
        assertThat(hold.status()).isEqualTo(HoldStatus.EXPIRED);
    }

    @Test
    void contradictingInstructionsAreContractViolations() {
        hold.cancel(T0);
        assertThatThrownBy(() -> hold.confirm(T0)).isInstanceOf(IllegalHoldTransitionException.class);

        var settled = Hold.reserve(UUID.randomUUID(), "wd-2", Money.of("1.00"), T0, TTL);
        settled.confirm(T0);
        assertThatThrownBy(() -> settled.cancel(T0)).isInstanceOf(IllegalHoldTransitionException.class);
    }
}
