package com.veggofresh.coupon.entity;

/** The window a per-customer limit is counted in. Calendar periods in Indian time (IST). */
public enum CouponPeriod {
    /** Midnight to midnight. */
    DAY,
    /** Monday to Sunday. */
    WEEK,
    /** The calendar month. */
    MONTH,
    /** Ever. */
    LIFETIME
}
