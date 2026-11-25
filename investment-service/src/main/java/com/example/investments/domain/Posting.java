package com.example.investments.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The bank-wide, double-entry view of a money booking, for Finance (ADR 0014, 0026). Balanced by
 * construction: one debit line and one credit line of the same amount. Securities are held in
 * custody off the bank's balance sheet, so only money is posted.
 */
public record Posting(UUID portfolioId, CashEntry.Type type, String reference, LocalDate valueDate,
        List<Line> lines) {

    public enum Side { DEBIT, CREDIT }

    public record Line(BookAccount bookAccount, Side side, Money amount) {
        public Line {
            Objects.requireNonNull(bookAccount, "bookAccount");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(amount, "amount");
        }
    }

    public Posting {
        lines = List.copyOf(lines);
    }

    /** The posting of one entry of a portfolio's money ledger. */
    public static Posting of(CashEntry entry) {
        var customer = BookAccount.customer(entry.portfolioId());
        var amount = entry.money();
        var counterpart = switch (entry.type()) {
            case FUNDING, PAYOUT, PAYOUT_RETURN -> BookAccount.TRANSFER_CLEARING;
            case BUY, SELL -> BookAccount.BROKER_SETTLEMENT;
        };
        // Money arriving on the portfolio is a liability to the customer: credit the customer.
        var lines = entry.type().isCredit()
                ? List.of(new Line(counterpart, Side.DEBIT, amount), new Line(customer, Side.CREDIT, amount))
                : List.of(new Line(customer, Side.DEBIT, amount), new Line(counterpart, Side.CREDIT, amount));
        return new Posting(entry.portfolioId(), entry.type(), entry.reference(),
                TradingCalendar.businessDayOf(entry.bookedAt()), lines);
    }
}
