package com.example.fixgateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param overdueAfter how long after its trading day ended an order still with the broker counts as
 *                     overdue (gauge {@code gateway.orders.overdue})
 */
@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(@Valid @NotNull Topics topics, @Valid @NotNull Fix fix,
        @NotNull Duration overdueAfter) {

    /**
     * @param orders     the gateway's command log, from investments
     * @param executions what the broker reported, for investments
     */
    public record Topics(@NotBlank String orders, @NotBlank String executions, @Positive int partitions) {
    }

    /**
     * The FIX 4.4 session to the broker; the gateway is the initiator.
     *
     * @param account           the bank's omnibus account at the broker (tag 1); customers are never named
     * @param reconnectInterval how long to wait before connecting again
     * @param leaderPoll        how often an instance without the session tries to take it, and the one
     *                          holding it checks that it still holds the lock
     * @param takeoverDelay     how long an instance that got the lock waits before it starts the session:
     *                          longer than {@code leaderPoll} and the lock check's timeout, so a former
     *                          holder that lost its lock connection has stopped its session by then
     */
    public record Fix(@NotBlank String host, @Positive int port, @NotBlank String senderCompId,
            @NotBlank String targetCompId, @NotBlank String account, @Positive int heartbeatSeconds,
            @NotNull Duration reconnectInterval, @NotNull Duration leaderPoll, @NotNull Duration takeoverDelay) {

        public Fix {
            if (takeoverDelay != null && leaderPoll != null
                    && takeoverDelay.compareTo(leaderPoll.plus(LOCK_CHECK_TIMEOUT)) <= 0) {
                throw new IllegalArgumentException("gateway.fix.takeover-delay (" + takeoverDelay
                        + ") must be longer than gateway.fix.leader-poll (" + leaderPoll + ") plus "
                        + LOCK_CHECK_TIMEOUT);
            }
        }
    }

    /** How long checking that the session lock is still held may take before it counts as lost. */
    public static final Duration LOCK_CHECK_TIMEOUT = Duration.ofSeconds(2);
}
