package com.veggofresh.admin.dto;

import com.veggofresh.vendor.entity.KycStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-facing DTO for a Vendor row in the Get-All-Vendors listing.
 * Combines data from the {@code vendor_shops} table with the Auth {@code users} table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminVendorDto {

    /** Shop (vendor record) UUID */
    private UUID shopId;

    /** Auth-user UUID */
    private UUID userId;

    /** Shop / business name */
    private String shopName;

    /** Owner's full name (from Shop.fullName) */
    private String ownerName;

    /** Contact email (shop.email preferred, fallback user.email) */
    private String email;

    /** Contact phone (shop.businessPhone preferred, fallback user.phone) */
    private String phone;

    /** City of operation */
    private String city;

    /** State */
    private String state;

    /** Business type (e.g. "Vegetable Retailer") */
    private String businessType;

    /** KYC verification status */
    private KycStatus kycStatus;

    /** Whether the shop is currently online / open */
    private boolean isOnline;

    /** Whether the owner account is blocked */
    private boolean isBlocked;

    /** Whether the owner account is phone-verified */
    private boolean isVerified;

    /** Store/profile image URL */
    private String storeImageUrl;

    /** Timestamp when the vendor shop record was created (registration) */
    private Instant registeredAt;

    /** Timestamp of the most recent update to the shop record */
    private Instant updatedAt;

    /** Submission timestamp for KYC review queue (null = not yet submitted) */
    private Instant applicationSubmittedAt;

    // ── Activity Counts (summary only — no full objects) ────────────────────

    /** Number of orders this vendor has accepted and fulfilled. */
    private long totalOrdersReceived;

    /** Number of active product listings in this vendor's shop. */
    private long totalListings;

    /** Number of complaints filed against or by this vendor. */
    private long totalComplaints;
}
