package com.veggofresh.delivery.service;

import com.veggofresh.delivery.dto.request.BasicInfoRequestDto;
import com.veggofresh.delivery.dto.request.VerificationStep3RequestDto;
import com.veggofresh.delivery.dto.response.OnboardingApplicationResponseDto;
import com.veggofresh.delivery.dto.response.OnboardingStatusResponseDto;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Rider onboarding: basic info, then three verification steps. Step 3 is the final step and SUBMITS the
 * application for review.
 *
 * <p>Editing rules (see {@link DeliveryApplicationGuard}): details can be changed while the application is a
 * draft or after it was rejected; they are locked while it is under review (the rider can withdraw it first)
 * and once it is approved.
 */
public interface DeliveryOnboardingService {

    /** Call right after OTP login succeeds -- tells the app exactly which screen to show next. Never creates anything. */
    OnboardingStatusResponseDto getStatus(UUID userId);

    /** The saved application, so any screen can be prefilled when the rider goes back to fix something. */
    OnboardingApplicationResponseDto getApplication(UUID userId);

    /** Name and city. The vehicle type is entered in step 2 with the other vehicle details. */
    OnboardingStatusResponseDto submitBasicInfo(UUID userId, BasicInfoRequestDto request);

    /**
     * License number plus license photo. The photo is only required when none is on file yet, so fixing a
     * typo in the number does not force a re-upload.
     */
    OnboardingStatusResponseDto submitVerificationStep1(UUID userId, String licenseNumber, MultipartFile licensePhoto);

    /**
     * Vehicle type, plate, model, year plus the insurance document (required only when none is on file yet).
     */
    OnboardingStatusResponseDto submitVerificationStep2(UUID userId, String vehicleType, String plateNumber,
                                                          String vehicleModel, Integer manufactureYear,
                                                          MultipartFile insurancePhoto);

    /**
     * Bank details plus the bank proof document (required only when none is on file yet). This is the FINAL
     * step: it submits the application for review.
     */
    OnboardingStatusResponseDto submitVerificationStep3(UUID userId, VerificationStep3RequestDto request,
                                                          MultipartFile bankProof);

    /** Takes a submitted application back out of the review queue so the rider can fix something and submit again. */
    OnboardingStatusResponseDto withdraw(UUID userId);
}
