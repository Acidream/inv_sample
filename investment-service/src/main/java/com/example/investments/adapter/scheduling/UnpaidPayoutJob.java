package com.example.investments.adapter.scheduling;

import com.example.investments.application.MoneyMovements;
import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tries unpaid payouts again once a day (ADR 0026): money the current account refused, e.g. while it
 * was blocked, stays on the portfolio until it is paid. An unblocked account triggers a retry at once;
 * this catches everything else.
 */
@Component
public class UnpaidPayoutJob {

    private static final Logger log = LoggerFactory.getLogger(UnpaidPayoutJob.class);
    private static final int BATCH_SIZE = 100;

    private final MoneyMovements money;
    private final Clock clock;

    UnpaidPayoutJob(MoneyMovements money, Clock clock) {
        this.money = money;
        this.clock = clock;
    }

    @Scheduled(cron = "${investments.jobs.unpaid-payouts-cron}", zone = "Europe/Berlin")
    @SchedulerLock(name = "unpaid-payouts", lockAtMostFor = "PT30M")
    public void run() {
        // Each retried payout leaves UNPAID, so the next batch starts with the rest.
        int retried;
        int total = 0;
        do {
            retried = money.retryUnpaid(clock.instant(), BATCH_SIZE);
            total += retried;
        } while (retried == BATCH_SIZE);
        if (total > 0) {
            log.info("Retried {} unpaid payouts", total);
        }
    }
}
