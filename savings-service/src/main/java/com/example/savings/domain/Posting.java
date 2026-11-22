package com.example.savings.domain;

import static com.example.savings.domain.BookAccount.INTEREST_EXPENSE;
import static com.example.savings.domain.BookAccount.INTEREST_PAYABLE;
import static com.example.savings.domain.BookAccount.PAYMENTS_CLEARING;
import static com.example.savings.domain.PostingLine.credit;
import static com.example.savings.domain.PostingLine.debit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A double-entry posting: lines whose debits equal their credits. An unbalanced posting can't be
 * constructed, so whatever Savings sends to Finance balances by construction.
 *
 * <p>The customer's ledger ({@link LedgerEntry}) stays the source of truth for balances; a posting
 * is the bank-wide view of the same booking, plus the interest accruals the customer doesn't see.
 *
 * @param accountId the savings account the posting concerns (also the message key, for ordering)
 * @param valueDate the business day the posting belongs to in the books; an accrual computed after
 *                  midnight still belongs to the day it was earned on
 */
public record Posting(UUID accountId, Type type, String reference, LocalDate valueDate, Instant bookedAt,
        List<PostingLine> lines) {

    public enum Type {
        DEPOSIT,
        WITHDRAWAL,
        INTEREST_ACCRUAL,
        INTEREST_PAYOUT
    }

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(bookedAt, "bookedAt");
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("A posting needs at least two lines: " + lines);
        }
        var sum = lines.stream().map(PostingLine::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() != 0) {
            throw new IllegalArgumentException("Unbalanced posting, debits minus credits = " + sum + ": " + lines);
        }
    }

    /** The bank-wide side of a customer booking. Holds are not bookings and never get here. */
    public static Posting of(LedgerEntry entry, ZoneId businessZone) {
        var customer = BookAccount.customer(entry.accountId());
        var amount = entry.amount().amount().abs();
        var valueDate = LocalDate.ofInstant(entry.bookedAt(), businessZone);
        return switch (entry.type()) {
            case DEPOSIT -> new Posting(entry.accountId(), Type.DEPOSIT, entry.reference(), valueDate,
                    entry.bookedAt(), List.of(debit(PAYMENTS_CLEARING, amount), credit(customer, amount)));
            case WITHDRAWAL -> new Posting(entry.accountId(), Type.WITHDRAWAL, entry.reference(), valueDate,
                    entry.bookedAt(), List.of(debit(customer, amount), credit(PAYMENTS_CLEARING, amount)));
            case INTEREST -> new Posting(entry.accountId(), Type.INTEREST_PAYOUT, entry.reference(), valueDate,
                    entry.bookedAt(), List.of(debit(INTEREST_PAYABLE, amount), credit(customer, amount)));
        };
    }

    /**
     * One day's interest: an expense for the bank, owed to the customer until the monthly payout.
     * Sub-cent precision; the payout later moves the rounded amount from payable to the customer.
     */
    public static Posting interestAccrual(UUID accountId, LocalDate day, BigDecimal interest, Instant bookedAt) {
        return new Posting(accountId, Type.INTEREST_ACCRUAL, "accrual:" + day, day, bookedAt,
                List.of(debit(INTEREST_EXPENSE, interest), credit(INTEREST_PAYABLE, interest)));
    }
}
