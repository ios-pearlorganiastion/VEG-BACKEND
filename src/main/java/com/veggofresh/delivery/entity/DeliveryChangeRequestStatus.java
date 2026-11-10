package com.veggofresh.delivery.entity;

/** Life of a change request. Only PENDING can move; the other three are final. */
public enum DeliveryChangeRequestStatus {
    /** Waiting for an admin. The live details are untouched. */
    PENDING,
    /** The admin approved it: the new details are now live. */
    APPROVED,
    /** The admin refused it, with a reason. The live details stay as they were. */
    REJECTED,
    /** The rider took it back before an admin decided. */
    CANCELLED
}
