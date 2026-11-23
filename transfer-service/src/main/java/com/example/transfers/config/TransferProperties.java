package com.example.transfers.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param awaitingAccountsTimeout how long a request may wait for its accounts to become known
 */
@Validated
@ConfigurationProperties("transfers")
public record TransferProperties(
        @Valid @NotNull Topics topics,
        @Valid @NotNull Outbox outbox,
        @NotNull Duration awaitingAccountsTimeout) {

    public record Topics(@NotBlank String transferRequests, @NotBlank String transferEvents,
            @NotBlank String savingsInstructions, @NotBlank String currentAccountInstructions,
            @NotBlank String savingsEvents, @NotBlank String currentAccountEvents, @Positive int partitions) {
    }

    public record Outbox(@Positive long pollIntervalMs, @Positive int batchSize) {
    }
}
