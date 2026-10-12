package com.veggofresh.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One KYC document slot. Every document type is always listed: a type the partner has not uploaded is
 * returned with {@code status = "NOT_UPLOADED"}, {@code uploaded = false} and a null {@code fileUrl}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryKycDocumentDto {
    /** LICENSE, INSURANCE, BACKGROUND_CHECK or BANK_INFO. */
    private String documentType;
    /** PENDING, VERIFIED, EXPIRING, REJECTED, or NOT_UPLOADED. */
    private String status;
    private String fileUrl;
    private boolean uploaded;
    private LocalDate expiryDate;
    private Instant uploadedAt;
    private Instant lastUpdatedAt;
}
