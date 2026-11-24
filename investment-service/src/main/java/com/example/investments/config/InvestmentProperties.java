package com.example.investments.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param overdueAfter     how long after its trading day ended an order still with the broker counts
 *                         as overdue (gauge {@code investments.orders.overdue}, ADR 0027)
 * @param unpaidAlertAfter how long a payout may stay unpaid before gauge
 *                         {@code investments.payouts.unpaid.overdue} counts it (ADR 0026)
 */
@Validated
@ConfigurationProperties("investments")
public record InvestmentProperties(
        @Valid @NotNull Topics topics,
        @NotNull Duration overdueAfter,
        @NotNull Duration unpaidAlertAfter) {

    /**
     * @param commands             the service's own command log
     * @param instructions         the transfer service's instructions to portfolios
     * @param accountEvents        what portfolios publish for the transfer service
     * @param transferRequests     the transfer service's command log, for funding, refunds and proceeds
     * @param transferEvents       outcomes of transfers
     * @param currentAccountEvents for unblocked accounts, to retry unpaid payouts
     * @param brokerOrders         the gateway's command log
     * @param brokerExecutions     what the broker reported, through the gateway
     * @param postings             for Finance
     */
    public record Topics(@NotBlank String commands, @NotBlank String instructions, @NotBlank String accountEvents,
            @NotBlank String transferRequests, @NotBlank String transferEvents, @NotBlank String currentAccountEvents,
            @NotBlank String brokerOrders, @NotBlank String brokerExecutions, @NotBlank String postings,
            @Positive int partitions) {
    }
}
