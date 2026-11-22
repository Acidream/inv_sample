package com.example.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-01-01T09:00:00Z");

    private final Account account = Account.open(UUID.randomUUID(), new BigDecimal("0.0365"), NOW, BERLIN);

    @Test
    void depositProducesLedgerEntryWithRunningBalance() {
        account.deposit(Money.of("10.00"), "p1", NOW);
        var entry = account.deposit(Money.of("5.50"), "p2", NOW);

        assertThat(entry.type()).isEqualTo(EntryType.DEPOSIT);
        assertThat(entry.amount()).isEqualTo(Money.of("5.50"));
        assertThat(entry.balanceAfter()).isEqualTo(Money.of("15.50"));
        assertThat(account.balance()).isEqualTo(Money.of("15.50"));
    }

    @Test
    void withdrawalDebitsAtOnceAndNeverOverdraws() {
        account.deposit(Money.of("100.00"), "t1", NOW);

        var entry = account.withdraw(Money.of("60.00"), "t2", NOW);

        assertThat(entry.type()).isEqualTo(EntryType.WITHDRAWAL);
        assertThat(entry.amount()).isEqualTo(Money.of("-60.00"));
        assertThat(account.balance()).isEqualTo(Money.of("40.00"));
        assertThat(account.canWithdraw(Money.of("40.00"))).isTrue();
        assertThat(account.canWithdraw(Money.of("40.01"))).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> account.withdraw(Money.of("40.01"), "t3", NOW));
        assertThat(account.balance()).isEqualTo(Money.of("40.00"));
    }

    @Test
    void aReturnedWithdrawalIsCreditedBack() {
        account.deposit(Money.of("100.00"), "t1", NOW);
        account.withdraw(Money.of("60.00"), "t2", NOW);

        var entry = account.creditReturnedWithdrawal(Money.of("60.00"), "t2", NOW);

        assertThat(entry.type()).isEqualTo(EntryType.WITHDRAWAL_RETURN);
        assertThat(entry.reference()).isEqualTo("t2");
        assertThat(account.balance()).isEqualTo(Money.of("100.00"));
    }

    @Test
    void interestStartsOnTheOpeningDayAndMustBeAccruedWithoutGaps() {
        assertThat(account.nextAccrualDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThatIllegalStateException()
                .isThrownBy(() -> account.accrueDay(LocalDate.of(2026, 1, 2), Money.of("1000.00")));
    }

    @Test
    void payoutRoundsToCentsAndCarriesTheRemainder() {
        account.deposit(Money.of("1000.00"), "p1", NOW);
        account.accrueDay(LocalDate.of(2026, 1, 1), Money.of("1000.00"));   // 0.10
        account.accrueDay(LocalDate.of(2026, 1, 2), Money.of("999.95"));    // 0.099995

        var entry = account.payOutInterest(YearMonth.of(2026, 1), NOW).orElseThrow();

        assertThat(entry.amount()).isEqualTo(Money.of("0.20"));
        assertThat(entry.reference()).isEqualTo("interest:2026-01");
        assertThat(account.accruedInterest()).isEqualByComparingTo("-0.000005");
    }

    @Test
    void nothingToPayBelowHalfACent() {
        account.accrueDay(LocalDate.of(2026, 1, 1), Money.of("1.00"));

        assertThat(account.payOutInterest(YearMonth.of(2026, 1), NOW)).isEmpty();
    }
}
