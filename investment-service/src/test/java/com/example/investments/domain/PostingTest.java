package com.example.investments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every money booking is a balanced double-entry posting (ADR 0014, 0026). */
class PostingTest {

    private static final Instant AT = Instant.parse("2026-11-24T10:00:00Z");
    private static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static CashEntry entry(CashEntry.Type type) {
        var amount = new BigDecimal("12.34");
        var signed = type.isCredit() ? amount : amount.negate();
        var trade = type == CashEntry.Type.BUY || type == CashEntry.Type.SELL
                ? new CashEntry.Trade("IE00B4L5Y983", Quantity.of("1"), Price.of("12.34"),
                        type == CashEntry.Type.SELL ? BigDecimal.ZERO : null)
                : null;
        return new CashEntry(PORTFOLIO, 1, type, signed, new BigDecimal("100.00"), "ref", null, trade, AT);
    }

    private static BigDecimal total(Posting posting, Posting.Side side) {
        return posting.lines().stream().filter(line -> line.side() == side)
                .map(line -> line.amount().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @ParameterizedTest
    @EnumSource(CashEntry.Type.class)
    void of_AnyEntry_DebitsEqualCredits(CashEntry.Type type) {
        var posting = Posting.of(entry(type));

        assertThat(posting.lines()).hasSize(2);
        assertThat(total(posting, Posting.Side.DEBIT)).isEqualByComparingTo(total(posting, Posting.Side.CREDIT))
                .isEqualByComparingTo("12.34");
    }

    @ParameterizedTest
    @EnumSource(CashEntry.Type.class)
    void of_AnyEntry_CreditsTheCustomerForMoneyArrivingAndDebitsThemForMoneyLeaving(CashEntry.Type type) {
        var posting = Posting.of(entry(type));

        var customerSide = posting.lines().stream()
                .filter(line -> line.bookAccount().equals(BookAccount.customer(PORTFOLIO)))
                .findFirst().orElseThrow().side();
        assertThat(customerSide).isEqualTo(type.isCredit() ? Posting.Side.CREDIT : Posting.Side.DEBIT);
    }

    @ParameterizedTest
    @EnumSource(value = CashEntry.Type.class, names = {"BUY", "SELL"})
    void of_Fill_MovesMoneyAgainstBrokerSettlement(CashEntry.Type type) {
        var posting = Posting.of(entry(type));

        assertThat(posting.lines()).extracting(Posting.Line::bookAccount).contains(BookAccount.BROKER_SETTLEMENT);
    }

    @ParameterizedTest
    @EnumSource(value = CashEntry.Type.class, names = {"FUNDING", "PAYOUT", "PAYOUT_RETURN"})
    void of_Transfer_MovesMoneyAgainstTransferClearing(CashEntry.Type type) {
        var posting = Posting.of(entry(type));

        assertThat(posting.lines()).extracting(Posting.Line::bookAccount).contains(BookAccount.TRANSFER_CLEARING);
    }

    @ParameterizedTest
    @EnumSource(CashEntry.Type.class)
    void of_EntryBookedOnAWeekday_IsValuedOnThatBerlinDay(CashEntry.Type type) {
        assertThat(Posting.of(entry(type)).valueDate()).isEqualTo(LocalDate.parse("2026-11-24"));
    }
}
