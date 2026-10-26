package com.veggofresh.coupon.entity;

/** Who may use a coupon. "Earlier orders" never include cancelled ones (a pending order does count). */
public enum CouponAudience {
    /** Every customer. */
    EVERYONE,
    /** Only customers with no earlier non-cancelled order (first-time offers). */
    NEW_CUSTOMERS,
    /** Only customers with at least {@code minPreviousOrders} earlier non-cancelled orders (loyalty offers). */
    RETURNING_CUSTOMERS
}
