package com.example.savings.application;

import com.example.savings.application.port.AccountEventPublisher;
import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.Ledger;
import com.example.savings.domain.Account;
import com.example.savings.domain.AccountEvent.AccountOpened;
import com.example.savings.domain.LedgerEntry;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    public record Statement(List<LedgerEntry> entries, Optional<StatementCursor> next) {
    }

    private final Accounts accounts;
    private final Ledger ledger;
    private final AccountEventPublisher events;
    private final SavingsPolicy policy;
    private final Clock clock;

    public AccountService(Accounts accounts, Ledger ledger, AccountEventPublisher events, SavingsPolicy policy,
            Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public Account open(UUID customerId, BigDecimal annualRate) {
        var account = Account.open(customerId, annualRate, clock.instant(), policy.businessZone());
        accounts.add(account);
        events.publish(new AccountOpened(account.id(), customerId, account.annualRate()));
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.get(id);
    }

    @Transactional(readOnly = true)
    public Statement statement(UUID accountId, StatementCursor after, int limit) {
        accounts.get(accountId);
        var page = ledger.page(accountId, after, limit + 1);
        if (page.size() <= limit) {
            return new Statement(page, Optional.empty());
        }
        var entries = page.subList(0, limit);
        var last = entries.getLast();
        return new Statement(entries, Optional.of(new StatementCursor(last.bookedAt(), last.id())));
    }
}
