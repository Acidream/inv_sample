package com.example.savings.application;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Business settings.
 *
 * @param businessZone time zone that defines a calendar day for interest accrual
 * @param holdTtl      how long reserved funds wait for a confirmation before they are released
 */
public record SavingsPolicy(ZoneId businessZone, Duration holdTtl) {
}
