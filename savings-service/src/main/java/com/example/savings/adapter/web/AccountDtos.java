package com.example.savings.adapter.web;

import com.example.savings.application.AccountService.Statement;
import com.example.savings.domain.Account;
import com.example.savings.domain.LedgerEntry;
import com.example.savings.domain.Money;
import com.example.savings.domain.SavingsProduct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    /** @param productCode a product on sale, see {@code GET /products} */
    record OpenAccountRequest(@NotNull UUID customerId, @NotBlank @Size(max = 40) String productCode) {
    }

    /** A product version: its terms. {@code annualRate} is a decimal fraction ("0.0250" = 2.5 % p.a.). */
    record ProductResponse(String code, int version, String name, String annualRate, String payoutFrequency) {

        static ProductResponse from(SavingsProduct product) {
            return new ProductResponse(product.code(), product.version(), product.name(),
                    product.annualRate().toPlainString(), product.payoutFrequency().name());
        }
    }

    record ProductsResponse(List<ProductResponse> items) {

        static ProductsResponse from(List<SavingsProduct> products) {
            return new ProductsResponse(products.stream().map(ProductResponse::from).toList());
        }
    }

    /** {@code product} is the version the account was opened on; its terms apply for the account's life. */
    record AccountResponse(UUID id, UUID customerId, ProductResponse product, String currency, String balance,
            String accruedInterest, Instant openedAt) {

        static AccountResponse from(Account account) {
            return new AccountResponse(account.id(), account.customerId(), ProductResponse.from(account.product()),
                    Money.CURRENCY, account.balance().toString(), account.accruedInterest().toPlainString(),
                    account.openedAt());
        }
    }

    /** {@code entryNo} numbers the account's entries 1, 2, 3, ... in booking order. */
    record EntryResponse(long id, long entryNo, String type, String amount, String balanceAfter,
            String reference, Instant bookedAt) {

        static EntryResponse from(LedgerEntry entry) {
            return new EntryResponse(entry.id(), entry.entryNo(), entry.type().name(), entry.amount().toString(),
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
