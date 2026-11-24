package com.example.investments.application.port;

import com.example.investments.domain.CashEntry;
import com.example.investments.domain.Portfolio;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Portfolios and their money ledger. */
public interface Portfolios {

    Optional<Portfolio> find(UUID portfolioId);

    /**
     * Finds the portfolio and locks it until the transaction ends. Everything that changes a portfolio,
     * its orders, holdings or payouts takes this lock first: its inputs arrive on several topics,
     * consumed concurrently.
     */
    Optional<Portfolio> lock(UUID portfolioId);

    void add(Portfolio portfolio);

    /** Stores the portfolio's balance and the entries booked on it in this transaction. */
    void update(Portfolio portfolio, List<CashEntry> newEntries);

    /** Newest first; {@code beforeEntryNo} continues a page (keyset pagination, ADR 0011). */
    List<CashEntry> entries(UUID portfolioId, Long beforeEntryNo, int limit);
}
