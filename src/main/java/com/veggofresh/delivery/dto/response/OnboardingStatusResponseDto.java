package com.veggofresh.delivery.dto.response;

import com.veggofresh.delivery.entity.DeliveryApplicationState;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * Tells the app which screen to show. Drive navigation from {@link #nextAction} and {@link #applicationState},
 * not from {@link #kycStatus}: kycStatus is PENDING from the very first save, so on its own it cannot tell a
 * rider who just started from one whose application is waiting for review.
 */
@Getter
@Builder
public class OnboardingStatusResponseDto {
    private boolean hasBasicInfo;
    private int verificationStep;

    /** True once the application has been sent (under review, approved or rejected); false for a draft. */
    private boolean isSubmitted;

    private DeliveryKycStatus kycStatus;
    private OnboardingNextAction nextAction;

    /** DRAFT, UNDER_REVIEW, APPROVED or REJECTED. */
    private DeliveryApplicationState applicationState;

    /** When the current submission was sent; null for a draft. */
    private Instant submittedAt;

    /** How many times the rider has submitted. */
    private int submissionCount;

    /** How many times the application has been rejected. */
    private int rejectionCount;

    /** Why the current rejection happened. Only present while the state is REJECTED. */
    private String rejectionReason;

    /** The sections the admin flagged (BASIC_INFO, LICENSE, VEHICLE, BANK). Only present while REJECTED; may be empty. */
    private List<DeliveryKycSection> rejectedSections;

    /** True when the details may be edited (draft or rejected). False while under review or once approved. */
    private boolean canEdit;

    /** True only while the application is under review. */
    private boolean canWithdraw;
}
