package com.example.savings.application;

import java.time.ZoneId;

/**
 * Business settings.
 *
 * @param businessZone time zone that defines a calendar day for interest accrual and value dates
 */
public record SavingsPolicy(ZoneId businessZone) {
}
