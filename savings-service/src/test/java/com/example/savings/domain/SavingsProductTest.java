package com.example.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SavingsProductTest {

    @Test
    void theRateIsKeptWithFourFractionDigits() {
        var product = new SavingsProduct("INSTANT_ACCESS", 1, "Instant access", new BigDecimal("0.025"),
                PayoutFrequency.MONTHLY);

        assertThat(product.annualRate().toPlainString()).isEqualTo("0.0250");
    }

    @Test
    void invalidTermsAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new SavingsProduct("instant", 1, "x", BigDecimal.ONE, PayoutFrequency.MONTHLY));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new SavingsProduct("INSTANT", 0, "x", BigDecimal.ONE, PayoutFrequency.MONTHLY));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new SavingsProduct("INSTANT", 1, "x", new BigDecimal("-0.01"), PayoutFrequency.MONTHLY));
    }
}
