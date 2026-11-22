package com.example.savings.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("savings")
public record SavingsProperties(
        @NotNull ZoneId businessZone,
        @Valid @NotNull Topics topics,
        @Valid @NotNull Outbox outbox) {

    public record Topics(@NotBlank String paymentInstructions, @NotBlank String accountEvents,
            @NotBlank String postings, @Positive int partitions) {
    }

    public record Outbox(@Positive long pollIntervalMs, @Positive int batchSize) {
    }
}
