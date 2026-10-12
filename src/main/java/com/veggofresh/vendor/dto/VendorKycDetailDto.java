package com.veggofresh.vendor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything an admin needs to approve or reject a vendor application: the original review fields
 * (names unchanged from {@link VendorKycReviewDto}) plus all onboarding data, documents and bank details.
 * Returned only by GET /api/admin/vendors/kyc/{shopId}; the pending list still returns VendorKycReviewDto.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorKycDetailDto {

    // ---- same fields (and names) as VendorKycReviewDto ----
    private UUID shopId;
    private UUID ownerUserId;
    private String businessName;
    private String ownerFullName;
    private String businessPhone;
    private String email;
    private String businessType;
    private String kycStatus;
    private String rejectionReason;
    private Instant submittedAt;

    // ---- new: the rest of the onboarding data ----
    /** Phone number the vendor logs in with (from the login account). */
    private String loginPhone;
    private String businessLicenseNumber;
    private Instant registeredAt;
    private Instant lastUpdatedAt;
    private VendorKycAddressDto address;
    private VendorKycStoreDto store;
    private VendorKycBankDto bank;
    private VendorKycProgressDto onboardingProgress;
    private List<VendorKycDocumentDto> documents;
    private boolean allDocumentsUploaded;
    private List<String> missingDocuments;
}
