package com.example.investments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An account in the bank's books as investments sees it. These are logical accounts: Finance maps
 * them to its chart of accounts. A copy of savings' type, not a shared one (ADR 0022).
 *
 * <ul>
 *   <li>{@code customer:<portfolio id>}: what the bank owes one customer on a portfolio (a liability).</li>
 *   <li>{@code transfer-clearing}: money in transit between the current account and the portfolio.</li>
 *   <li>{@code broker-settlement}: money owed to or by the broker for fills.</li>
 * </ul>
 */
public record BookAccount(String code) {

    public static final BookAccount TRANSFER_CLEARING = new BookAccount("transfer-clearing");
    public static final BookAccount BROKER_SETTLEMENT = new BookAccount("broker-settlement");

    public BookAccount {
        Objects.requireNonNull(code, "code");
    }

    public static BookAccount customer(UUID portfolioId) {
        return new BookAccount("customer:" + portfolioId);
    }
}
