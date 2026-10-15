package com.veggofresh.complaint.entity;

/**
 * Lifecycle status of a complaint:
 * <ul>
 *   <li><b>PENDING</b>: Initial state upon registration. Awaiting admin review.</li>
 *   <li><b>RESOLVED</b>: Admin marked as resolved. Original user can reopen this if issue persists.</li>
 *   <li><b>UNRESOLVED</b>: Admin marked as unresolved/rejected.</li>
 *   <li><b>REOPENED</b>: Complainant reopened a previously resolved complaint.</li>
 * </ul>
 */
public enum ComplaintStatus {
    PENDING,
    RESOLVED,
    UNRESOLVED,
    REOPENED
}
