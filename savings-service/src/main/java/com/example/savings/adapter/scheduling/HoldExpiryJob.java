package com.example.savings.adapter.scheduling;

import com.example.savings.application.HoldExpiryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
    private static final int BATCH_SIZE = 500;

    private final HoldExpiryService expiry;

    HoldExpiryJob(HoldExpiryService expiry) {
        this.expiry = expiry;
    }

    /** One batch per run; a backlog drains over the next runs. */
    @Scheduled(cron = "${savings.jobs.hold-expiry-cron}")
    @SchedulerLock(name = "hold-expiry", lockAtMostFor = "PT5M")
    public void run() {
        for (var holdId : expiry.dueForExpiry(BATCH_SIZE)) {
            try {
                expiry.expire(holdId);
            } catch (OptimisticLockingFailureException e) {
                log.info("Hold {} changed concurrently (probably confirmed or cancelled), skipping", holdId);
            }
        }
    }
}
