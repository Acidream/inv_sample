package com.example.investments.application;

import com.example.investments.application.port.Outbound;
import com.example.investments.application.port.Portfolios;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Money;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.Posting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Books money on a locked portfolio: every entry on its ledger also becomes a posting for Finance in
 * the same transaction (ADR 0014, 0026), so the two can't drift apart.
 */
@Component
class Books {

    private final Portfolios portfolios;
    private final Outbound outbound;

    Books(Portfolios portfolios, Outbound outbound) {
        this.portfolios = portfolios;
        this.outbound = outbound;
    }

    /** Starts booking on a portfolio the caller has locked; {@link Ledger#save} stores what was booked. */
    Ledger of(Portfolio portfolio) {
        return new Ledger(portfolio);
    }

    final class Ledger {

        private final Portfolio portfolio;
        private final List<CashEntry> entries = new ArrayList<>();

        private Ledger(Portfolio portfolio) {
            this.portfolio = portfolio;
        }

        Portfolio portfolio() {
            return portfolio;
        }

        CashEntry credit(CashEntry.Type type, Money amount, String reference, String orderId, CashEntry.Trade trade,
                Instant at) {
            return posted(portfolio.credit(type, amount, reference, orderId, trade, at));
        }

        CashEntry debit(CashEntry.Type type, Money amount, String reference, String orderId, CashEntry.Trade trade,
                Instant at) {
            return posted(portfolio.debit(type, amount, reference, orderId, trade, at));
        }

        void save() {
            if (!entries.isEmpty()) {
                portfolios.update(portfolio, entries);
            }
        }

        private CashEntry posted(CashEntry entry) {
            entries.add(entry);
            outbound.posting(Posting.of(entry), entry.bookedAt());
            return entry;
        }
    }
}
