package com.example.savings.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Interest maths. Day count convention ACT/365 Fixed: every calendar day earns 1/365 of the
 * annual rate, also in leap years. Accruals keep {@link #ACCRUAL_SCALE} fraction digits;
 * rounding to cents happens only once, at payout.
 */
public final class Interest {

    public static final int ACCRUAL_SCALE = 10;
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private Interest() {
    }

    /** Interest earned for one day on the given end-of-day balance. A zero balance earns nothing. */
    public static BigDecimal forOneDay(Money endOfDayBalance, BigDecimal annualRate) {
        if (!endOfDayBalance.isPositive()) {
            return BigDecimal.ZERO.setScale(ACCRUAL_SCALE);
        }
        return endOfDayBalance.amount()
                .multiply(annualRate)
                .divide(DAYS_PER_YEAR, ACCRUAL_SCALE, RoundingMode.HALF_EVEN);
    }
}
