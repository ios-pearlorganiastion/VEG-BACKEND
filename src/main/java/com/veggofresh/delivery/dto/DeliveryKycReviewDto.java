package com.veggofresh.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Cross-module DTO -- consumed by Admin's AdminDeliveryKycController. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryKycReviewDto {
    private UUID userId;
    private String fullName;
    private String phone;
    private String vehicleType;
    private String vehicleModel;
    private String plateNumber;
    private String licenseNumber;
    private String kycStatus;
    private String rejectionReason;

    /** When the rider submitted the application (real submission time, not the last-updated time). */
    private Instant submittedAt;

    /** How many times the rider has submitted. */
    private int submissionCount;

    /** How many times this rider has already been rejected. */
    private int rejectionCount;

    /** True when this rider was rejected before: the admin is looking at a resubmission. */
    private boolean resubmission;
}
