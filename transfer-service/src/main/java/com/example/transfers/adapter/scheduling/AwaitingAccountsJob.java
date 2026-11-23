package com.example.transfers.adapter.scheduling;

import com.example.transfers.application.TransferService;
import com.example.transfers.config.TransferProperties;
import java.time.Clock;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails requests whose accounts never became known, e.g. a typo in an account id. A request for an
 * account that was just opened waits a moment instead: its opening event and the request travel on
 * different topics and may arrive in either order. The wait counts from when this service received
 * the request, so a request delivered late gets the full timeout too (ADR 0023).
 */
@Component
public class AwaitingAccountsJob {

    private static final Logger log = LoggerFactory.getLogger(AwaitingAccountsJob.class);
    private static final int BATCH_SIZE = 100;

    private final TransferService transfers;
    private final Clock clock;
    private final Duration timeout;

    AwaitingAccountsJob(TransferService transfers, Clock clock, TransferProperties properties) {
        this.transfers = transfers;
        this.clock = clock;
        this.timeout = properties.awaitingAccountsTimeout();
    }

    @Scheduled(cron = "${transfers.jobs.awaiting-accounts-cron}", zone = "UTC")
    @SchedulerLock(name = "awaiting-accounts", lockAtMostFor = "PT5M")
    public void run() {
        var before = clock.instant().minus(timeout);
        int failed;
        int total = 0;
        do {
            failed = transfers.failAwaitingSince(before, BATCH_SIZE);
            total += failed;
        } while (failed == BATCH_SIZE);
        if (total > 0) {
            log.warn("Failed {} transfers whose accounts were not known within {}", total, timeout);
        }
    }
}
