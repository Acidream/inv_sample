package com.example.savings.adapter.scheduling;

import com.example.savings.application.InterestService;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly: accrue yesterday's interest for every account, one transaction per account, walking the
 * table in keyset-paginated chunks. A conflict with a concurrent deposit skips the account; the
 * next run catches it up because accrual always resumes from the account's last accrued day.
 */
@Component
public class InterestAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualJob.class);
    private static final int CHUNK_SIZE = 500;

    private final InterestService interest;

    InterestAccrualJob(InterestService interest) {
        this.interest = interest;
    }

    @Scheduled(cron = "${savings.jobs.interest-accrual-cron}", zone = "${savings.business-zone}")
    @SchedulerLock(name = "interest-accrual", lockAtMostFor = "PT2H")
    public void run() {
        var through = interest.lastCompletedDay();
        var after = new UUID(0, 0);
        int accrued = 0;
        int skipped = 0;
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
                }
            }
            after = ids.getLast();
        }
        log.info("Interest accrued through {} for {} accounts ({} skipped)", through, accrued, skipped);
    }
}
