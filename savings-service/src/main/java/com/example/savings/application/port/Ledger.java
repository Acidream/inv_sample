package com.example.savings.application.port;

import com.example.savings.application.StatementCursor;
import com.example.savings.domain.LedgerEntry;
import com.example.savings.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Append-only ledger. There is deliberately no update or delete. */
public interface Ledger {

    void append(LedgerEntry entry);

    /** Balance after the last entry booked strictly before {@code before}; zero if none. */
    Money balanceAt(UUID accountId, Instant before);

    /** Newest first. With a cursor, only entries older than the cursor. */
    List<LedgerEntry> page(UUID accountId, StatementCursor after, int limit);
}
