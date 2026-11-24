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
     * @param leaderPoll        how often an instance without the session tries to take it
     */
    public record Fix(@NotBlank String host, @Positive int port, @NotBlank String senderCompId,
            @NotBlank String targetCompId, @NotBlank String account, @Positive int heartbeatSeconds,
            @NotNull Duration reconnectInterval, @NotNull Duration leaderPoll) {
    }
}
