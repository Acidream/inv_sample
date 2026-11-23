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
        @Valid @NotNull Topics topics) {

    /**
     * @param savingsCommands  savings' own command log (ADR 0019)
     * @param transferRequests where savings asks the transfer service for money (owned by Payments)
     * @param transferEvents   outcomes of transfers (owned by Payments)
     */
    public record Topics(@NotBlank String paymentInstructions, @NotBlank String accountEvents,
            @NotBlank String postings, @NotBlank String savingsCommands, @NotBlank String transferRequests,
            @NotBlank String transferEvents, @Positive int partitions) {
    }
}
