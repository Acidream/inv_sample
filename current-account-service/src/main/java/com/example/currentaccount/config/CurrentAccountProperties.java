package com.example.currentaccount.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("current-account")
public record CurrentAccountProperties(@Valid @NotNull Topics topics, @Valid @NotNull Outbox outbox) {

    /**
     * @param commands     the service's own command log
     * @param instructions the transfer service's instructions to current accounts
     * @param events       what current accounts publish
     */
    public record Topics(@NotBlank String commands, @NotBlank String instructions, @NotBlank String events,
            @Positive int partitions) {
    }

    public record Outbox(@Positive long pollIntervalMs, @Positive int batchSize) {
    }
}
