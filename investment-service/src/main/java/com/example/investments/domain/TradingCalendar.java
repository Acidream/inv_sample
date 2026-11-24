package com.example.investments.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Trading days, counted in Berlin. Every weekday is one: there is no exchange holiday calendar yet
 * (ADR 0026). An order placed on a weekend trades on the following Monday.
 */
public final class TradingCalendar {

    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private TradingCalendar() {
    }

    /** The trading day an order placed at this moment belongs to. */
    public static LocalDate tradingDayOf(Instant at) {
        var day = at.atZone(ZONE).toLocalDate();
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        return day;
    }

    /** The business day a booking at this moment belongs to in the books (a posting's value date). */
    public static LocalDate businessDayOf(Instant at) {
        return at.atZone(ZONE).toLocalDate();
    }

    /** The moment a trading day ends: midnight Berlin. */
    public static Instant endOf(LocalDate tradingDay) {
        return tradingDay.plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
