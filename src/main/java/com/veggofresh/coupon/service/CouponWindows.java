package com.veggofresh.coupon.service;

import com.veggofresh.coupon.entity.CouponPeriod;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** The calendar windows of per-customer limits, in Indian time. Pure and easy to test. */
public final class CouponWindows {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final Instant FAR_FUTURE = Instant.parse("2999-12-31T00:00:00Z");

    private CouponWindows() {
    }

    /** [from, to) in UTC instants. */
    public record Window(Instant from, Instant to) {
    }

    public static Window of(CouponPeriod period, Instant now) {
        LocalDate today = now.atZone(IST).toLocalDate();
        switch (period) {
            case DAY:
                return new Window(start(today), start(today.plusDays(1)));
            case WEEK: {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return new Window(start(monday), start(monday.plusDays(7)));
            }
            case MONTH: {
                LocalDate first = today.withDayOfMonth(1);
                return new Window(start(first), start(first.plusMonths(1)));
            }
            case LIFETIME:
            default:
                return new Window(Instant.EPOCH, FAR_FUTURE);
        }
    }

    /** "today", "this week" ... for messages; empty for LIFETIME. */
    public static String describe(CouponPeriod period) {
        switch (period) {
            case DAY:
                return "today";
            case WEEK:
                return "this week";
            case MONTH:
                return "this month";
            default:
                return "";
        }
    }

    private static Instant start(LocalDate d) {
        return d.atStartOfDay(IST).toInstant();
    }
}
