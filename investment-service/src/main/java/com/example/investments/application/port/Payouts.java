package com.example.investments.application.port;

import com.example.investments.domain.Payout;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Payouts {

    Optional<Payout> find(UUID payoutId);

    /** The payout whose current attempt is this transfer. */
    Optional<Payout> findByTransfer(String transferId);

    void add(Payout payout);

    void update(Payout payout);

    List<Payout> unpaid(UUID portfolioId);

    /** Ids of unpaid payouts, oldest first; of one reference account if given. */
    List<UUID> unpaidIds(UUID referenceAccountId, int limit);

    /** Unpaid since before the given time (ADR 0026's alert). */
    long countUnpaidSince(Instant before);
}
