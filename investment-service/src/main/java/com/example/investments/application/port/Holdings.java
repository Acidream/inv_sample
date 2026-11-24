package com.example.investments.application.port;

import com.example.investments.domain.Holding;
import java.util.List;
import java.util.UUID;

/** Holdings and their lots. Callers hold the portfolio's lock (see {@link Portfolios#lock}). */
public interface Holdings {

    /** The holding with its lots that have units left, oldest first; empty if there is none yet. */
    Holding get(UUID portfolioId, String isin);

    /** Stores the holding and its lots: new ones inserted, changed ones updated. */
    void save(Holding holding);

    /** Holdings with units left, or reserved. */
    List<Holding> of(UUID portfolioId);
}
