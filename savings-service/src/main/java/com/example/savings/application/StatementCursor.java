package com.example.savings.application;

import java.time.Instant;

/** Position in a statement for keyset pagination: (bookedAt, entryNo) of the last entry seen. */
public record StatementCursor(Instant bookedAt, long entryNo) {
}
