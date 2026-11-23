package com.example.currentaccount.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrentAccountTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private static CurrentAccount account(String overdraftLimit, String funds) {
        var account = CurrentAccount.open(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(overdraftLimit), NOW);
        if (!funds.equals("0")) {
            account.receive("salary", Money.of(funds), NOW);
        }
        return account;
    }

    @Test
    void opensWithAZeroBalance() {
        var account = account("200", "0");
        assertThat(account.balance()).isEqualByComparingTo("0");
        assertThat(account.available()).isEqualByComparingTo("200");
        assertThat(account.status()).isEqualTo(CurrentAccount.Status.OPEN);
    }

    @Test
    void debitsDownToMinusTheOverdraftLimit() {
        var account = account("200", "100");
        var decision = account.debit("t1", Money.of("300"), NOW);
        assertThat(decision.answer().carriedOut()).isTrue();
        assertThat(decision.booking()).hasValueSatisfying(booking -> {
            assertThat(booking.type()).isEqualTo(Booking.Type.TRANSFER_DEBIT);
            assertThat(booking.amount()).isEqualByComparingTo("-300");
            assertThat(booking.balanceAfter()).isEqualByComparingTo("-200");
        });
        assertThat(account.available()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsADebitOneCentBeyondTheLimit() {
        var account = account("200", "100");
        var decision = account.debit("t1", Money.of("300.01"), NOW);
        assertThat(decision.answer().rejection()).hasValue(CurrentAccount.OVERDRAFT_EXCEEDED);
        assertThat(decision.booking()).isEmpty();
        assertThat(account.balance()).isEqualByComparingTo("100");
    }

    @Test
    void creditsAddToTheBalance() {
        var account = account("0", "0");
        var decision = account.credit("t1", Money.of("40"), NOW);
        assertThat(decision.answer().carriedOut()).isTrue();
        assertThat(decision.booking()).hasValueSatisfying(b -> assertThat(b.balanceAfter()).isEqualByComparingTo("40"));
    }

    @Test
    void aBlockedAccountRefusesTransfersButBooksIncomingPayments() {
        var account = account("100", "50");
        account.block("suspected fraud");
        assertThat(account.debit("t1", Money.of("1"), NOW).answer().rejection())
                .hasValue(CurrentAccount.BLOCKED_REASON);
        assertThat(account.credit("t2", Money.of("1"), NOW).answer().rejection())
                .hasValue(CurrentAccount.BLOCKED_REASON);
        assertThat(account.available()).isEqualByComparingTo("0");

        account.receive("p2", Money.of("10"), NOW);
        assertThat(account.balance()).isEqualByComparingTo("60");

        account.unblock();
        assertThat(account.blockReason()).isEmpty();
        assertThat(account.debit("t3", Money.of("160"), NOW).answer().carriedOut()).isTrue();
    }

    @Test
    void aBlockNeedsAReason() {
        assertThatThrownBy(() -> account("0", "0").block(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOverdraftLimitIsZeroOrPositiveInCents() {
        assertThat(CurrentAccount.overdraftLimit(new BigDecimal("5"))).isEqualByComparingTo("5.00");
        assertThatThrownBy(() -> CurrentAccount.overdraftLimit(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrentAccount.overdraftLimit(new BigDecimal("0.001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recognisesTheSameOpening() {
        var account = account("200", "0");
        assertThat(account.openedAs(account.customerId(), new BigDecimal("200.00"))).isTrue();
        assertThat(account.openedAs(account.customerId(), new BigDecimal("100"))).isFalse();
        assertThat(account.openedAs(UUID.randomUUID(), new BigDecimal("200"))).isFalse();
    }

    @Test
    void anAnswerRecognisesTheSameInstruction() {
        var answer = account("0", "100").debit("t1", Money.of("30"), NOW).answer();
        assertThat(answer.answers(TransferAnswer.Direction.DEBIT, Money.of("30.00"))).isTrue();
        assertThat(answer.answers(TransferAnswer.Direction.DEBIT, Money.of("31"))).isFalse();
        assertThat(answer.answers(TransferAnswer.Direction.CREDIT, Money.of("30"))).isFalse();
    }
}
