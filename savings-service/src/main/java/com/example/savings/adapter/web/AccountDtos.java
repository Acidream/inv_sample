package com.example.savings.adapter.web;

import com.example.savings.application.AccountService.Statement;
import com.example.savings.domain.Account;
import com.example.savings.domain.LedgerEntry;
import com.example.savings.domain.Money;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API representations. Amounts are JSON strings ("100.00"), not numbers: many JSON clients parse
 * numbers as binary floating point, which cannot represent most cent values exactly.
 */
final class AccountDtos {

    private AccountDtos() {
    }

    record OpenAccountRequest(
            @NotNull UUID customerId,
            @NotNull @DecimalMin("0") @DecimalMax("0.2") @Digits(integer = 1, fraction = 4) BigDecimal annualRate) {
    }

    record AccountResponse(UUID id, UUID customerId, String annualRate, String currency, String balance,
            String accruedInterest, Instant openedAt) {

        static AccountResponse from(Account account) {
            return new AccountResponse(account.id(), account.customerId(), account.annualRate().toPlainString(),
                    Money.CURRENCY, account.balance().toString(), account.accruedInterest().toPlainString(),
                    account.openedAt());
        }
    }

    record EntryResponse(long id, String type, String amount, String balanceAfter, String reference,
            Instant bookedAt) {

        static EntryResponse from(LedgerEntry entry) {
            return new EntryResponse(entry.id(), entry.type().name(), entry.amount().toString(),
                    entry.balanceAfter().toString(), entry.reference(), entry.bookedAt());
        }
    }

    record StatementResponse(List<EntryResponse> items, String nextCursor) {

        static StatementResponse from(Statement statement) {
            return new StatementResponse(
                    statement.entries().stream().map(EntryResponse::from).toList(),
                    statement.next().map(StatementCursorCodec::encode).orElse(null));
        }
    }
}
