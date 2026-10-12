package com.veggofresh.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything an admin needs to approve or reject a delivery partner application: the original review
 * fields (names unchanged from {@link DeliveryKycReviewDto}) plus all onboarding data, documents and bank
 * details. Returned only by GET /api/admin/delivery-partners/kyc/{userId}; the pending list still returns
 * DeliveryKycReviewDto.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryKycDetailDto {

    // ---- same fields (and names) as DeliveryKycReviewDto ----
    private UUID userId;
    private String fullName;
    private String phone;
    private String vehicleType;
    private String vehicleModel;
    private String plateNumber;
    private String licenseNumber;
    private String kycStatus;
    private String rejectionReason;
    private Instant submittedAt;

    // ---- new: the rest of the onboarding data ----
    private String email;
    private String avatarUrl;
    private String cityOfOperation;
    private String vehicleColor;
    private Integer manufactureYear;
    private DeliveryKycEmergencyContactDto emergencyContact;
    private DeliveryKycBankDto bank;
    /** Onboarding step reached (3 = application submitted). */
    private int onboardingStep;
    private boolean basicInfoCompleted;
    private Instant registeredAt;
    private Instant lastUpdatedAt;
    private List<DeliveryKycDocumentDto> documents;
    private boolean allDocumentsUploaded;
    private List<String> missingDocuments;
}
