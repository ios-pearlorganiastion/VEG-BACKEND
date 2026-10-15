package com.veggofresh.notification.entity;

/**
 * Machine-readable type of a notification. Drives client-side rendering
 * decisions (which screen to refresh, which icon to show) and doubles as a
 * stable audit label for the row in {@code notifications}.
 *
 * <p>Ordered to match the event coverage implemented for this module:
 * order lifecycle â†’ delivery lifecycle â†’ stock/payment/admin events.
 */
public enum NotificationType {
    // â”€â”€ Order lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    ORDER_PLACED,
    NEW_ORDER_REQUEST,          // vendor-side broadcast-inbox ping (order awaiting acceptance)
    ORDER_ACCEPTED,             // vendor-side: this shop won the accept race
    ORDER_CONFIRMED,            // customer-side
    ORDER_PACKED,               // customer-side: vendor started/prepared
    ORDER_READY_FOR_PICKUP,     // customer-side: packed & handed off to delivery network
    ORDER_OUT_FOR_DELIVERY,
    ORDER_DELIVERED,
    ORDER_CANCELLED,

    // â”€â”€ Delivery lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    DELIVERY_ASSIGNED,          // partner accepted / was assigned an order
    DELIVERY_PICKUP_OTP_GENERATED,
    DELIVERY_PICKUP_OTP_VERIFIED,
    DELIVERY_DROP_OTP_GENERATED,
    DELIVERY_DROP_OTP_VERIFIED,

    // â”€â”€ Stock / money / feedback â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    LOW_STOCK_ALERT,
    PAYMENT_SUCCESS,
    PAYMENT_FAILED,
    REVIEW_RECEIVED,

    // â”€â”€ Admin â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    ADMIN_ANNOUNCEMENT,

    // â”€â”€ Complaint lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    COMPLAINT_REGISTERED,
    COMPLAINT_RESOLVED,
    COMPLAINT_UNRESOLVED,
    COMPLAINT_REOPENED
}