package com.example.savings.application;

import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.Holds;
import com.example.savings.application.port.SavingsMetrics;
import com.example.savings.domain.AccountEvent.FundsReleased;
import com.example.savings.domain.AccountEvent.ReleaseReason;
import com.example.savings.domain.Hold;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Releases holds that Payments never answered, so funds don't stay blocked forever. */
@Service
public class HoldExpiryService {

    private final Holds holds;
    private final Accounts accounts;
    private final AccountEventPublisher events;
    private final SavingsMetrics metrics;
    private final Clock clock;

    public HoldExpiryService(Holds holds, Accounts accounts, AccountEventPublisher events, SavingsMetrics metrics,
            Clock clock) {
        this.holds = holds;
        this.accounts = accounts;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UUID> dueForExpiry(int limit) {
        return holds.idsDueForExpiry(clock.instant(), limit);
    }

    @Transactional
    public void expire(UUID holdId) {
        var hold = holds.get(holdId);
        if (hold.expire(clock.instant()) != Hold.Outcome.APPLIED) {
            return;
        }
        var account = accounts.get(hold.accountId());
        account.releaseHold(hold.amount());
        events.publish(new FundsReleased(account.id(), hold.requestId(), hold.amount(), ReleaseReason.EXPIRED));
        metrics.withdrawal("expired");
    }
}
