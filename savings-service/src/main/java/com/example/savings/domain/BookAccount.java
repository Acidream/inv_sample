package com.example.savings.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An account in the bank's books as Savings sees it. These are logical accounts: Finance maps them to
 * its chart of accounts, so Savings never needs to know general-ledger codes.
 *
 * <ul>
 *   <li>{@code customer:<id>}: what the bank owes one customer (a liability).</li>
 *   <li>{@code transfer-clearing}: money in transit between the current account and savings.</li>
 *   <li>{@code interest-expense}: what interest costs the bank (profit and loss).</li>
 *   <li>{@code interest-payable}: interest earned by customers but not yet credited (a liability).</li>
 * </ul>
 */
public record BookAccount(String code) {

    public static final BookAccount TRANSFER_CLEARING = new BookAccount("transfer-clearing");
    public static final BookAccount INTEREST_EXPENSE = new BookAccount("interest-expense");
    public static final BookAccount INTEREST_PAYABLE = new BookAccount("interest-payable");

    public BookAccount {
        Objects.requireNonNull(code, "code");
    }

    public static BookAccount customer(UUID accountId) {
        return new BookAccount("customer:" + accountId);
    }
}
