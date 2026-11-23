package com.example.savings.adapter.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.savings.application.InterestService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

class InterestAccrualJobTest {

    private static final LocalDate YESTERDAY = LocalDate.parse("2026-01-31");
    private static final UUID BROKEN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BUSY = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FINE = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final InterestService interest = mock(InterestService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void run_OneAccountFails_AccruesTheOthers() {
        when(interest.lastCompletedDay()).thenReturn(YESTERDAY);
        when(interest.accountsDueForAccrual(eq(YESTERDAY), any(), anyInt()))
                .thenReturn(List.of(BROKEN, BUSY, FINE))
                .thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("duplicate accrual"))
                .when(interest).accrueThrough(BROKEN, YESTERDAY);
        doThrow(new OptimisticLockingFailureException("deposit")).when(interest).accrueThrough(BUSY, YESTERDAY);
        var job = new InterestAccrualJob(interest, registry);

        job.run();

        verify(interest).accrueThrough(FINE, YESTERDAY);
        // A conflict is expected and caught up next run; anything else is a failure worth an alert.
        assertThat(registry.get("savings.interest.accrual.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void lagGauge_AccountsBehind_ReportsDaysBehind() {
        when(interest.daysBehind()).thenReturn(3L);
        new InterestAccrualJob(interest, registry);

        assertThat(registry.get("savings.interest.accrual.lag.days").gauge().value()).isEqualTo(3);
    }
}
