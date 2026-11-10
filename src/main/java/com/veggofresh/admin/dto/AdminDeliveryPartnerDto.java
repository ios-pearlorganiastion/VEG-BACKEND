package com.veggofresh.admin.dto;

import com.veggofresh.delivery.entity.DeliveryKycStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-facing DTO for a Delivery Partner row in the Get-All-Delivery-Partners listing.
 * Combines data from {@code delivery_partners} and Auth {@code users} tables.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminDeliveryPartnerDto {

    /** DeliveryPartnerProfile UUID */
    private UUID profileId;

    /** Auth-user UUID */
    private UUID userId;

    /** Full name (from profile.fullName) */
    private String fullName;

    /** Email (profile.email preferred, fallback user.email) */
    private String email;

    /** Phone number (from auth user) */
    private String phone;

    /** Avatar / profile photo URL */
    private String avatarUrl;

    /** Vehicle type (e.g. "BIKE", "SCOOTER") */
    private String vehicleType;

    /** Vehicle model */
    private String vehicleModel;

    /** License plate number */
    private String plateNumber;

    /** City of operation */
    private String cityOfOperation;

    /** KYC verification status. PENDING for BOTH a draft and a submitted application: use applicationState. */
    private DeliveryKycStatus kycStatus;

    /**
     * DRAFT, UNDER_REVIEW, APPROVED or REJECTED. Use this, not kycStatus, to tell a rider who has only started
     * onboarding (DRAFT) from one whose application is waiting for review (UNDER_REVIEW).
     */
    private String applicationState;

    /** When the application was submitted; null for a draft. */
    private Instant submittedAt;

    /** How many times the rider has submitted. */
    private int submissionCount;

    /** How many times the application has been rejected. */
    private int rejectionCount;

    /** Whether the partner is currently online */
    private boolean isOnline;

    /** Whether the auth account is blocked */
    private boolean isBlocked;

    /** Whether the phone is OTP-verified */
    private boolean isVerified;

    /**
     * Onboarding progress step.
     * 0 = not started, 1 = license done, 2 = vehicle done, 3 = bank done (submitted)
     */
    private int verificationStep;

    /** Timestamp when the profile record was created */
    private Instant registeredAt;

    /** Timestamp of the most recent profile update */
    private Instant updatedAt;

    // ── Activity Counts (summary only — no full objects) ────────────────────

    /** Total number of deliveries completed (DELIVERED status) by this partner. */
    private long totalDeliveries;

    /** Number of complaints filed by this delivery partner. */
    private long totalComplaints;
}
