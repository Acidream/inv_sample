package com.example.savings.adapter.scheduling;

import com.example.savings.application.InterestService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly: accrue yesterday's interest for every account, one transaction per account, walking the
 * table in keyset-paginated chunks. A failure affects only its own account: a conflict with a
 * concurrent deposit is skipped quietly, any other error is logged and counted, and the run goes on
 * with the next account. The next run catches every skipped account up, because accrual always
 * resumes from the account's last accrued day.
 *
 * <p>Two metrics make a broken run visible: {@code savings_interest_accrual_failed_total} and
 * {@code savings_interest_accrual_lag_days} (an account that keeps failing, or a run that never
 * happened, keeps the lag above 0).
 */
@Component
public class InterestAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualJob.class);
    private static final int CHUNK_SIZE = 500;

    private final InterestService interest;
    private final Counter failures;

    InterestAccrualJob(InterestService interest, MeterRegistry registry) {
        this.interest = interest;
        // Registered up front at 0 so an increase() alert sees the first failure.
        this.failures = registry.counter("savings.interest.accrual.failed");
        Gauge.builder("savings.interest.accrual.lag.days", interest, InterestService::daysBehind)
                .description("Complete days the account furthest behind has not accrued yet")
                .register(registry);
    }

    @Scheduled(cron = "${savings.jobs.interest-accrual-cron}", zone = "${savings.business-zone}")
    @SchedulerLock(name = "interest-accrual", lockAtMostFor = "PT2H")
    public void run() {
        var through = interest.lastCompletedDay();
        var after = new UUID(0, 0);
        int accrued = 0;
        int skipped = 0;
        int failed = 0;
        while (true) {
            var ids = interest.accountsDueForAccrual(through, after, CHUNK_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            for (var id : ids) {
                try {
                    interest.accrueThrough(id, through);
                    accrued++;
                } catch (OptimisticLockingFailureException e) {
                    skipped++;
                    log.warn("Concurrent change on account {}, will catch up next run", id);
                } catch (RuntimeException e) {
                    failed++;
                    failures.increment();
                    log.error("Accruing interest for account {} through {} failed; continuing with the next account",
                            id, through, e);
                }
            }
            after = ids.getLast();
        }
        if (failed > 0) {
            log.error("Interest accrued through {} for {} accounts ({} skipped, {} failed)", through, accrued, skipped,
                    failed);
        } else {
            log.info("Interest accrued through {} for {} accounts ({} skipped)", through, accrued, skipped);
        }
    }
}
