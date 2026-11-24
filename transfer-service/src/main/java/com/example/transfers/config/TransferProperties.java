package com.example.transfers.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param awaitingAccountsTimeout how long a request may wait for its accounts to become known,
 *                                counted from when this service received it
 * @param stuckAfter              how long a transfer may wait for a ledger's answer before the
 *                                {@code transfers.stuck} gauge counts it (ADR 0023)
 */
@Validated
@ConfigurationProperties("transfers")
public record TransferProperties(
        @Valid @NotNull Topics topics,
        @NotNull Duration awaitingAccountsTimeout,
        @NotNull Duration stuckAfter) {

    public record Topics(@NotBlank String transferRequests, @NotBlank String transferEvents,
            @NotBlank String savingsInstructions, @NotBlank String currentAccountInstructions,
            @NotBlank String savingsEvents, @NotBlank String currentAccountEvents,
            @NotBlank String investmentInstructions, @NotBlank String investmentEvents, @Positive int partitions) {
    }
}
