package com.example.investments.application;

import com.example.investments.application.port.Portfolios;
import com.example.investments.application.port.PostingPublisher;
import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Portfolio;
import com.example.investments.domain.Posting;
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
    private final PostingPublisher postings;

    Books(Portfolios portfolios, PostingPublisher postings) {
        this.portfolios = portfolios;
        this.postings = postings;
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

        void book(CashEntry.Booking booking) {
            var entry = portfolio.book(booking);
            entries.add(entry);
            postings.publish(Posting.of(entry), entry.bookedAt());
        }

        void save() {
            if (!entries.isEmpty()) {
                portfolios.update(portfolio, entries);
            }
        }
    }
}
