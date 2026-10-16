package com.veggofresh.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-facing DTO for a Customer row in the Get-All-Customers listing.
 * Combines data from {@code customer_profiles} and Auth {@code users} tables.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCustomerDto {

    /** CustomerProfile UUID */
    private UUID profileId;

    /** Auth-user UUID */
    private UUID userId;

    /** Customer's display name */
    private String fullName;

    /** Email (profile.email preferred, fallback user.email) */
    private String email;

    /** Phone number (from auth user) */
    private String phone;

    /** Avatar / profile picture URL */
    private String avatarUrl;

    /** Whether the account is blocked */
    private boolean isBlocked;

    /** Whether the phone is OTP-verified */
    private boolean isVerified;

    /**
     * Human-readable account state shown in the UI.
     * Values: "Active", "Blocked", "Unverified"
     */
    private String accountState;

    /** Timestamp when the auth user record was created (registration) */
    private Instant registeredAt;

    /** Timestamp of the most recent profile update */
    private Instant updatedAt;

    // ── Activity Counts (summary only — no full objects) ────────────────────

    /** Number of orders placed by this customer. */
    private long totalOrders;

    /** Number of complaints filed by this customer across all categories. */
    private long totalComplaints;
}
