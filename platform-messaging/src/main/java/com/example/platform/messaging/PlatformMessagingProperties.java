package com.example.platform.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code platform.messaging.*}. The defaults suit every service so far; a service overrides them in
 * its {@code application.yml}, tests shorten the poll interval and switch the retention job off
 * ({@code platform.messaging.retention.cron=-}).
 */
@ConfigurationProperties("platform.messaging")
public record PlatformMessagingProperties(@DefaultValue Outbox outbox, @DefaultValue Retention retention) {

    /** @param pollIntervalMs read by {@link OutboxRelay}'s {@code @Scheduled} directly */
    public record Outbox(@DefaultValue("200") long pollIntervalMs, @DefaultValue("100") int batchSize) {
        public Outbox {
            if (pollIntervalMs <= 0 || batchSize <= 0) {
                throw new IllegalArgumentException("Outbox poll interval and batch size must be positive");
            }
        }
    }

    /**
     * @param cron                    read by {@link RetentionJob}'s {@code @Scheduled} directly; "-" disables it
     * @param publishedOutboxMessages how long sent outbox rows are kept for diagnosis
     * @param processedMessages       how long consumed message ids are kept; must exceed any
     *                                possible redelivery
     */
    public record Retention(@DefaultValue("0 30 3 * * *") String cron,
            @DefaultValue("7d") Duration publishedOutboxMessages, @DefaultValue("30d") Duration processedMessages) {
    }
}
