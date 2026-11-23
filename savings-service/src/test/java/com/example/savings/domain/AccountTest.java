package com.example.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-01-01T09:00:00Z");

    private static final SavingsProduct PRODUCT =
            new SavingsProduct("TEST", 1, "Test", new BigDecimal("0.0365"), PayoutFrequency.MONTHLY);
    private static final InterestPeriod JANUARY = PayoutFrequency.MONTHLY.periodContaining(LocalDate.of(2026, 1, 1));
    private static final InterestPeriod FEBRUARY = PayoutFrequency.MONTHLY.periodContaining(LocalDate.of(2026, 2, 1));

    private final Account account = Account.open(UUID.randomUUID(), UUID.randomUUID(), PRODUCT, NOW, BERLIN);

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
    void aStoredAccountIsRebuiltWithTheSameState() {
        account.deposit(Money.of("10.00"), "p1", NOW);
        account.accrueDay(account.nextAccrualDate(), Money.of("10.00"));
        var stored = new Account.State(account.id(), account.customerId(), account.product(), account.balance(),
                account.accruedInterest(), account.lastAccrualDate(), account.openedAt(), 1, NOW, 7L);

        var restored = Account.restore(stored);

        assertThat(restored.state()).isEqualTo(stored);
        assertThat(restored.deposit(Money.of("1.00"), "p2", NOW).entryNo()).isEqualTo(2);
    }

    @Test
    void aStoredAccountMustNotBeOverdrawn() {
        var overdrawn = new Account.State(account.id(), account.customerId(), account.product(),
                Money.of("-0.01"), account.accruedInterest(), account.lastAccrualDate(), NOW, 0, NOW, 1L);

        assertThatIllegalArgumentException().isThrownBy(() -> Account.restore(overdrawn));
    }

    @Test
    void entriesAreNumberedInBookingOrder() {
        var first = account.deposit(Money.of("10.00"), "p1", NOW);
        var second = account.withdraw(Money.of("4.00"), "p2", NOW);
        var third = account.creditReturnedWithdrawal(Money.of("4.00"), "p2", NOW);

        assertThat(first.entryNo()).isEqualTo(1);
        assertThat(second.entryNo()).isEqualTo(2);
        assertThat(third.entryNo()).isEqualTo(3);
    }

    @Test
    void aBookerWhoseClockIsBehindDoesNotBookIntoThePast() {
        // Instance A books at 09:00:00.500; instance B's clock is 300 ms behind.
        var onA = account.deposit(Money.of("10.00"), "p1", NOW.plusMillis(500));
        var onB = account.deposit(Money.of("5.00"), "p2", NOW.plusMillis(200));
        var later = account.deposit(Money.of("1.00"), "p3", NOW.plusSeconds(1));

        assertThat(onB.bookedAt()).isEqualTo(onA.bookedAt());
        assertThat(onB.entryNo()).isGreaterThan(onA.entryNo());
        assertThat(later.bookedAt()).isEqualTo(NOW.plusSeconds(1));
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
    void theAccountEarnsItsProductsRate() {
        var interest = account.accrueDay(LocalDate.of(2026, 1, 1), Money.of("1000.00"));

        assertThat(account.annualRate()).isEqualByComparingTo("0.0365");
        assertThat(interest).isEqualByComparingTo("0.10");
    }

    @Test
    void payoutIsDueAtTheEndOfTheProductsPayoutPeriod() {
        var quarterly = Account.open(UUID.randomUUID(), UUID.randomUUID(),
                new SavingsProduct("Q", 1, "Quarterly", new BigDecimal("0.0365"), PayoutFrequency.QUARTERLY), NOW,
                BERLIN);

        assertThat(account.payoutDueAfter(LocalDate.of(2026, 1, 31))).isTrue();
        assertThat(quarterly.payoutDueAfter(LocalDate.of(2026, 1, 31))).isFalse();
        assertThat(quarterly.payoutDueAfter(LocalDate.of(2026, 3, 31))).isTrue();
        assertThat(quarterly.payoutPeriodOf(LocalDate.of(2026, 3, 31)).label()).isEqualTo("2026-Q1");
    }

    @Test
    void interestStartsOnTheOpeningDayAndMustBeAccruedWithoutGaps() {
        assertThat(account.nextAccrualDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThatIllegalStateException()
                .isThrownBy(() -> account.accrueDay(LocalDate.of(2026, 1, 2), Money.of("1000.00")));
    }

    @Test
    void payoutRoundsDownToCentsAndCarriesTheRemainder() {
        account.deposit(Money.of("1000.00"), "p1", NOW);
        account.accrueDay(LocalDate.of(2026, 1, 1), Money.of("1000.00"));   // 0.10
        account.accrueDay(LocalDate.of(2026, 1, 2), Money.of("999.95"));    // 0.099995

        var entry = account.payOutInterest(JANUARY, NOW).orElseThrow();

        assertThat(entry.amount()).isEqualTo(Money.of("0.19"));
        assertThat(entry.reference()).isEqualTo("interest:2026-01");
        assertThat(account.accruedInterest()).isEqualByComparingTo("0.009995");
    }

    @Test
    void nothingIsPaidBeforeAWholeCentIsEarned() {
        account.accrueDay(LocalDate.of(2026, 1, 1), Money.of("99.00"));    // 0.0099

        assertThat(account.payOutInterest(JANUARY, NOW)).isEmpty();
        assertThat(account.accruedInterest()).isEqualByComparingTo("0.0099");
    }

    @Test
    void theCarriedRemainderIsPaidOnceItAddsUpToACent() {
        account.accrueDay(LocalDate.of(2026, 1, 1), Money.of("99.00"));    // 0.0099
        account.payOutInterest(JANUARY, NOW);
        account.accrueDay(LocalDate.of(2026, 1, 2), Money.of("99.00"));    // + 0.0099 = 0.0198

        var entry = account.payOutInterest(FEBRUARY, NOW).orElseThrow();

        assertThat(entry.amount()).isEqualTo(Money.of("0.01"));
        assertThat(account.accruedInterest()).isEqualByComparingTo("0.0098");
    }
}
