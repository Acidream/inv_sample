package com.example.savings.domain;

import static com.example.savings.domain.BookAccount.INTEREST_EXPENSE;
import static com.example.savings.domain.BookAccount.INTEREST_PAYABLE;
import static com.example.savings.domain.BookAccount.TRANSFER_CLEARING;
import static com.example.savings.domain.PostingLine.credit;
import static com.example.savings.domain.PostingLine.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final BookAccount CUSTOMER = BookAccount.customer(ACCOUNT);
    private static final Instant NOON = Instant.parse("2026-03-02T11:00:00Z");

    @Test
    void depositMovesMoneyFromTransferClearingToTheCustomer() {
        var entry = new LedgerEntry(ACCOUNT, EntryType.DEPOSIT, Money.of("100.00"), Money.of("100.00"), "pay-1", NOON);

        var posting = Posting.of(entry, BERLIN);

        assertThat(posting.type()).isEqualTo(Posting.Type.DEPOSIT);
        assertThat(posting.reference()).isEqualTo("pay-1");
        assertThat(posting.lines()).containsExactly(
                debit(TRANSFER_CLEARING, new BigDecimal("100.00")), credit(CUSTOMER, new BigDecimal("100.00")));
    }

    @Test
    void withdrawalMovesMoneyFromTheCustomerToClearing() {
        var entry = new LedgerEntry(ACCOUNT, EntryType.WITHDRAWAL, Money.of("-40.00"), Money.of("60.00"), "wd-1",
                NOON);

        var posting = Posting.of(entry, BERLIN);

        assertThat(posting.type()).isEqualTo(Posting.Type.WITHDRAWAL);
        assertThat(posting.lines()).containsExactly(
                debit(CUSTOMER, new BigDecimal("40.00")), credit(TRANSFER_CLEARING, new BigDecimal("40.00")));
    }

    @Test
    void aReturnedWithdrawalMovesTheMoneyBackToTheCustomer() {
        var entry = new LedgerEntry(ACCOUNT, EntryType.WITHDRAWAL_RETURN, Money.of("40.00"), Money.of("100.00"),
                "wd-1", NOON);

        var posting = Posting.of(entry, BERLIN);

        assertThat(posting.type()).isEqualTo(Posting.Type.WITHDRAWAL_RETURN);
        assertThat(posting.lines()).containsExactly(
                debit(TRANSFER_CLEARING, new BigDecimal("40.00")), credit(CUSTOMER, new BigDecimal("40.00")));
    }

    @Test
    void interestPayoutSettlesThePayableToTheCustomer() {
        var entry = new LedgerEntry(ACCOUNT, EntryType.INTEREST, Money.of("3.10"), Money.of("1003.10"),
                "interest:2026-01", NOON);

        var posting = Posting.of(entry, BERLIN);

        assertThat(posting.type()).isEqualTo(Posting.Type.INTEREST_PAYOUT);
        assertThat(posting.lines()).containsExactly(
                debit(INTEREST_PAYABLE, new BigDecimal("3.10")), credit(CUSTOMER, new BigDecimal("3.10")));
    }

    @Test
    void accrualIsAnExpenseOwedToTheCustomerWithSubCentPrecision() {
        var day = LocalDate.parse("2026-01-31");

        var posting = Posting.interestAccrual(ACCOUNT, day, new BigDecimal("0.0027397260"), NOON);

        assertThat(posting.valueDate()).isEqualTo(day);
        assertThat(posting.reference()).isEqualTo("accrual:2026-01-31");
        assertThat(posting.lines()).containsExactly(
                debit(INTEREST_EXPENSE, new BigDecimal("0.0027397260")),
                credit(INTEREST_PAYABLE, new BigDecimal("0.0027397260")));
    }

    @Test
    void valueDateIsTheBusinessDayNotTheUtcDay() {
        // 23:30 UTC on 31 January is already 1 February in Berlin.
        var lateEvening = Instant.parse("2026-01-31T23:30:00Z");
        var entry = new LedgerEntry(ACCOUNT, EntryType.DEPOSIT, Money.of("1.00"), Money.of("1.00"), "pay-2",
                lateEvening);

        assertThat(Posting.of(entry, BERLIN).valueDate()).isEqualTo(LocalDate.parse("2026-02-01"));
    }

    @Test
    void anUnbalancedPostingCannotBeBuilt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> posting(debit(TRANSFER_CLEARING, new BigDecimal("100.00")),
                        credit(CUSTOMER, new BigDecimal("99.99"))))
                .withMessageContaining("Unbalanced");
    }

    @Test
    void aPostingNeedsTwoSides() {
        assertThatIllegalArgumentException().isThrownBy(() -> posting(debit(CUSTOMER, BigDecimal.ONE)));
    }

    @Test
    void linesMovePositiveAmountsWithAtMostTenFractionDigits() {
        assertThatIllegalArgumentException().isThrownBy(() -> debit(CUSTOMER, BigDecimal.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> debit(CUSTOMER, new BigDecimal("-1")));
        assertThatIllegalArgumentException().isThrownBy(() -> debit(CUSTOMER, new BigDecimal("0.00000000001")));
    }

    @Test
    void balanceIsCheckedAcrossMoreThanTwoLines() {
        var posting = posting(debit(TRANSFER_CLEARING, new BigDecimal("10.00")),
                credit(CUSTOMER, new BigDecimal("7.50")), credit(INTEREST_PAYABLE, new BigDecimal("2.5")));

        assertThat(posting.lines()).hasSize(3);
    }

    private static Posting posting(PostingLine... lines) {
        return new Posting(ACCOUNT, Posting.Type.DEPOSIT, "ref", LocalDate.parse("2026-03-02"), NOON, List.of(lines));
    }
}
