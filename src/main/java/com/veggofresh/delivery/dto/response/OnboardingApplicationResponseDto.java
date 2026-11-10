package com.veggofresh.delivery.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * The rider's saved application in one place, so every onboarding screen can be prefilled when the rider goes
 * back to fix something, and the "review your application" screen has everything it needs.
 */
@Getter
@Builder
public class OnboardingApplicationResponseDto {

    /** The same status block {@code GET /status} returns (state, next action, rejection reason...). */
    private OnboardingStatusResponseDto status;

    private BasicInfo basicInfo;
    private License license;
    private Vehicle vehicle;
    private Bank bank;

    @Getter
    @Builder
    public static class DocumentInfo {
        private boolean uploaded;
        private String fileUrl;
        /** PENDING, VERIFIED, REJECTED or EXPIRING; null when nothing is uploaded. */
        private String status;
    }

    @Getter
    @Builder
    public static class BasicInfo {
        private String fullName;
        private String cityOfOperation;
    }

    @Getter
    @Builder
    public static class License {
        private String licenseNumber;
        private DocumentInfo document;
    }

    @Getter
    @Builder
    public static class Vehicle {
        private String vehicleType;
        private String plateNumber;
        private String vehicleModel;
        private Integer manufactureYear;
        private DocumentInfo insurance;
    }

    @Getter
    @Builder
    public static class Bank {
        private String bankName;
        private String accountHolderName;
        private String accountNumber;
        private String ifscCode;
        private boolean agreedToPayoutTerms;
        private DocumentInfo proof;
    }
}
