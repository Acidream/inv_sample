package com.example.savings.application;

import com.example.savings.application.port.Accounts;
import com.example.savings.application.port.Ledger;
import com.example.savings.domain.Account;
import com.example.savings.domain.LedgerEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queries on accounts. Accounts are opened by orders (ADR 0020) and change only through messages
 * (ADR 0019), never through this service.
 */
@Service
public class AccountService {

    public record Statement(List<LedgerEntry> entries, Optional<StatementCursor> next) {
    }

    private final Accounts accounts;
    private final Ledger ledger;

    public AccountService(Accounts accounts, Ledger ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
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
        return new Statement(entries, Optional.of(new StatementCursor(last.bookedAt(), last.entryNo())));
    }
}
