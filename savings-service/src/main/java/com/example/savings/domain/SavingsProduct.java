package com.example.savings.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One version of a savings product: the terms a customer gets when opening an account (ADR 0017).
 * A version never changes once published. New terms are a new version, and an account keeps the
 * version it was opened on.
 *
 * @param annualRate decimal fraction per year, e.g. 0.0250 = 2.5 % p.a.
 */
public record SavingsProduct(String code, int version, String name, BigDecimal annualRate,
        PayoutFrequency payoutFrequency) {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    public SavingsProduct {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Product code must be upper case letters, digits and _: " + code);
        }
        if (version < 1) {
            throw new IllegalArgumentException("Product version must be positive: " + version);
        }
        Objects.requireNonNull(name, "name");
        if (annualRate.signum() < 0) {
            throw new IllegalArgumentException("Annual rate must not be negative: " + annualRate);
        }
        annualRate = annualRate.setScale(4, RoundingMode.UNNECESSARY);
        Objects.requireNonNull(payoutFrequency, "payoutFrequency");
    }
}
