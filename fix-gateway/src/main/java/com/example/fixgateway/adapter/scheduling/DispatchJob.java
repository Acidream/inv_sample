package com.example.fixgateway.adapter.scheduling;

import com.example.fixgateway.application.Dispatcher;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends waiting orders a moment after they arrive. Runs on every instance: only the one holding the
 * FIX session sends, and expiring unsent orders is safe anywhere (row locks).
 */
@Component
public class DispatchJob {

    private final Dispatcher dispatcher;
    private final Clock clock;

    DispatchJob(Dispatcher dispatcher, Clock clock) {
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${gateway.dispatch-interval-ms}")
    public void run() {
        dispatcher.sendWaiting(clock.instant());
    }
}
