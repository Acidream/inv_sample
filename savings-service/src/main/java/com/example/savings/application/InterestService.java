package com.example.savings.application;

import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.InterestAccruals;
import com.example.savings.application.port.Ledger;
import com.example.savings.application.port.SavingsMetrics;
import com.example.savings.domain.AccountEvent.InterestPaid;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily accrual and monthly payout. For every day not yet accrued, the end-of-day balance is read
 * from the ledger (not the current balance), so catching up after missed runs gives the same result
 * as running every night. Interest is paid out after the last day of each month has been accrued.
 */
@Service
public class InterestService {

    private final Accounts accounts;
    private final Ledger ledger;
    private final InterestAccruals accruals;
    private final AccountEventPublisher events;
    private final SavingsMetrics metrics;
    private final SavingsPolicy policy;
    private final Clock clock;

    public InterestService(Accounts accounts, Ledger ledger, InterestAccruals accruals, AccountEventPublisher events,
            SavingsMetrics metrics, SavingsPolicy policy, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.accruals = accruals;
        this.events = events;
        this.metrics = metrics;
        this.policy = policy;
        this.clock = clock;
    }

    /** The last day that is complete in the business time zone. */
    public LocalDate lastCompletedDay() {
        return LocalDate.now(clock.withZone(policy.businessZone())).minusDays(1);
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
            accruals.record(accountId, day, interest);

            if (day.equals(day.withDayOfMonth(day.lengthOfMonth()))) {
                var period = YearMonth.from(day);
                account.payOutInterest(period, clock.instant()).ifPresent(entry -> {
                    ledger.append(entry);
                    events.publish(new InterestPaid(accountId, period, entry.amount(), entry.balanceAfter()));
                    metrics.interestPaid(entry.amount());
                });
            }
        }
    }
}
