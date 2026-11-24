package com.example.investments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Something a customer can buy, identified by its ISIN, traded in EUR (ADR 0026). From reference
 * data; it can be taken off sale from a given time, after which it can still be sold, not bought.
 */
public record Instrument(String isin, String name, Type type, Instant offSaleFrom) {

    public enum Type { ETF, STOCK }

    /** Two letters of country, nine alphanumerics, one check digit. */
    public static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    public Instrument {
        Objects.requireNonNull(isin, "isin");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!ISIN.matcher(isin).matches()) {
            throw new IllegalArgumentException("Not an ISIN: " + isin);
        }
    }

    public boolean onSaleAt(Instant at) {
        return offSaleFrom == null || at.isBefore(offSaleFrom);
    }

    public Optional<Instant> offSale() {
        return Optional.ofNullable(offSaleFrom);
    }
}
