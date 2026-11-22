package com.example.savings.application;

import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.Ledger;
import com.example.savings.application.port.SavingsMetrics;
import com.example.savings.domain.AccountEvent.InterestPaid;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily accrual and monthly payout. For every day not yet accrued, the end-of-day balance is read
 * from the ledger (not the current balance), so a catch-up after missed runs accrues the same amounts
 * for the same days. One known difference (ADR 0010): a payout made during a catch-up is booked when
 * the catch-up runs, so the days between month end and that run don't earn interest on it. Interest
 * is paid out after the last day of each month has been accrued.
 */
@Service
public class InterestService {

    private final Accounts accounts;
    private final Ledger ledger;
    private final Bookkeeping bookkeeping;
    private final AccountEventPublisher events;
    private final SavingsMetrics metrics;
    private final SavingsPolicy policy;
    private final Clock clock;

    public InterestService(Accounts accounts, Ledger ledger, Bookkeeping bookkeeping, AccountEventPublisher events,
            SavingsMetrics metrics, SavingsPolicy policy, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.bookkeeping = bookkeeping;
        this.events = events;
        this.metrics = metrics;
        this.policy = policy;
        this.clock = clock;
    }

    /** The last day that is complete in the business time zone. */
    public LocalDate lastCompletedDay() {
        return LocalDate.now(clock.withZone(policy.businessZone())).minusDays(1);
    }

    /**
     * How many complete days the account furthest behind still has to accrue. 0 when every account is
     * accrued through yesterday; 1 between midnight and the end of the nightly run; more means a run
     * was missed or an account keeps failing.
     */
    @Transactional(readOnly = true)
    public long daysBehind() {
        var through = lastCompletedDay();
        return accounts.oldestAccrualDate()
                .map(oldest -> Math.max(0, ChronoUnit.DAYS.between(oldest, through)))
                .orElse(0L);
    }

    @Transactional(readOnly = true)
    public List<UUID> accountsDueForAccrual(LocalDate through, UUID after, int limit) {
        return accounts.idsDueForAccrual(through, after, limit);
    }

    @Transactional
    public void accrueThrough(UUID accountId, LocalDate through) {
        var account = accounts.get(accountId);
        for (var day = account.nextAccrualDate(); !day.isAfter(through); day = day.plusDays(1)) {
            var endOfDay = day.plusDays(1).atStartOfDay(policy.businessZone()).toInstant();
            var interest = account.accrueDay(day, ledger.balanceAt(accountId, endOfDay));
            bookkeeping.accrue(accountId, day, interest);

            if (day.equals(day.withDayOfMonth(day.lengthOfMonth()))) {
                var period = YearMonth.from(day);
                account.payOutInterest(period, clock.instant()).ifPresent(entry -> {
                    bookkeeping.book(entry);
                    events.publish(new InterestPaid(accountId, period, entry.amount(), entry.balanceAfter()));
                    metrics.interestPaid(entry.amount());
                });
            }
        }
        accounts.update(account);
    }
}
