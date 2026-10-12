package com.veggofresh.vendor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One KYC document slot. Every document type is always listed: a type the vendor has not uploaded is
 * returned with {@code status = "NOT_UPLOADED"}, {@code uploaded = false} and a null {@code fileUrl}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorKycDocumentDto {
    /** BUSINESS_LICENSE, TAX_ID or GOVERNMENT_ID. */
    private String documentType;
    /** PENDING, VERIFIED, EXPIRING, REJECTED, or NOT_UPLOADED. */
    private String status;
    private String fileUrl;
    private boolean uploaded;
    private Instant uploadedAt;
    private Instant lastUpdatedAt;
}
