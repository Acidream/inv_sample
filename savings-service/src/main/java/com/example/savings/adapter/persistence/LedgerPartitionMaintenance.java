package com.example.savings.adapter.persistence;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps monthly ledger partitions created ahead of time. Rows that would fall outside every monthly
 * partition land in ledger_entry_default, which should stay empty: a non-empty default partition
 * blocks creating the matching monthly partition and is worth an alert.
 */
@Component
public class LedgerPartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(LedgerPartitionMaintenance.class);
    private static final int MONTHS_AHEAD = 3;
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    private final JdbcClient jdbc;
    private final Clock clock;

    LedgerPartitionMaintenance(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @EventListener(ApplicationStartedEvent.class)
    @Scheduled(cron = "${savings.jobs.partition-maintenance-cron}", zone = "UTC")
    @SchedulerLock(name = "ledger-partition-maintenance", lockAtMostFor = "PT5M")
    public void ensurePartitions() {
        var current = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            var month = current.plusMonths(i);
            var from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            var to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            // DDL cannot take bind parameters; every value here is generated, none is user input.
            jdbc.sql("""
                    create table if not exists ledger_entry_%s partition of ledger_entry
                    for values from ('%s') to ('%s')""".formatted(month.format(SUFFIX), from, to)).update();
        }
        log.info("Ledger partitions ensured through {}", current.plusMonths(MONTHS_AHEAD));
    }
}
