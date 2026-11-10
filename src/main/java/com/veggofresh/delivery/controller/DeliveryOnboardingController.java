package com.veggofresh.delivery.controller;

import com.veggofresh.delivery.dto.request.BasicInfoRequestDto;
import com.veggofresh.delivery.dto.request.VerificationStep3RequestDto;
import com.veggofresh.delivery.dto.response.OnboardingApplicationResponseDto;
import com.veggofresh.delivery.dto.response.OnboardingStatusResponseDto;
import com.veggofresh.delivery.service.DeliveryOnboardingService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/delivery/onboarding")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY')")
public class DeliveryOnboardingController {

    private final DeliveryOnboardingService deliveryOnboardingService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<OnboardingStatusResponseDto>> getStatus() {
        var status = deliveryOnboardingService.getStatus(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(status, "Onboarding status retrieved successfully"));
    }

    /** The saved application in one call, to prefill every screen when the rider goes back to fix something. */
    @GetMapping("/application")
    public ResponseEntity<ApiResponse<OnboardingApplicationResponseDto>> getApplication() {
        var application = deliveryOnboardingService.getApplication(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(application, "Application retrieved successfully"));
    }

    @PutMapping("/basic-info")
    public ResponseEntity<ApiResponse<OnboardingStatusResponseDto>> submitBasicInfo(@Valid @RequestBody BasicInfoRequestDto request) {
        var status = deliveryOnboardingService.submitBasicInfo(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(status, "Basic info saved"));
    }

    /** The photo is only required when no license photo is on file yet. */
    @PostMapping("/verification/step-1")
    public ResponseEntity<ApiResponse<OnboardingStatusResponseDto>> submitStep1(
            @RequestParam String licenseNumber,
            @RequestParam(value = "photo", required = false) MultipartFile licensePhoto) {
        var status = deliveryOnboardingService.submitVerificationStep1(SecurityUtils.getCurrentUserId(), licenseNumber, licensePhoto);
        return ResponseEntity.ok(ApiResponse.success(status, "License details saved"));
    }

    /** Vehicle type now lives here, with the other vehicle details. The photo is only required when no insurance document is on file yet. */
    @PostMapping("/verification/step-2")
    public ResponseEntity<ApiResponse<OnboardingStatusResponseDto>> submitStep2(
            @RequestParam String vehicleType,
            @RequestParam String plateNumber,
            @RequestParam String vehicleModel,
            @RequestParam Integer manufactureYear,
            @RequestParam(value = "photo", required = false) MultipartFile insurancePhoto) {
        var status = deliveryOnboardingService.submitVerificationStep2(SecurityUtils.getCurrentUserId(),
                vehicleType, plateNumber, vehicleModel, manufactureYear, insurancePhoto);
        return ResponseEntity.ok(ApiResponse.success(status, "Vehicle details saved"));
    }

    /**
     * The FINAL step: bank details plus the bank proof document, sent as multipart form data. Saving it submits
     * the application for review. The photo is only required when no bank proof is on file yet.
     * PUT is kept for compatibility with the old method; POST is equivalent.
     */
    @RequestMapping(value = "/verification/step-3", method = {RequestMethod.PUT, RequestMethod.POST},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<OnboardingStatusResponseDto>> submitStep3(
            @RequestParam String bankName,
            @RequestParam String accountHolderName,
            @RequestParam String accountNumber,
            @RequestParam String ifscCode,
            @RequestParam boolean agreedToPayoutTerms,
            @RequestParam(value = "photo", required = false) MultipartFile bankProof) {
        VerificationStep3RequestDto request = new VerificationStep3RequestDto();
        request.setBankName(bankName);
        request.setAccountHolderName(accountHolderName);
        request.setAccountNumber(accountNumber);
        request.setIfscCode(ifscCode);
        request.setAgreedToPayoutTerms(agreedToPayoutTerms);
        var status = deliveryOnboardingService.submitVerificationStep3(SecurityUtils.getCurrentUserId(), request, bankProof);
        return ResponseEntity.ok(ApiResponse.success(status, "Application submitted for review"));
    }

    /** Takes a submitted application out of the review queue so the rider can fix something and submit again. */
    @PostMapping("/withdraw")
    public ResponseEntity<ApiResponse<OnboardingStatusResponseDto>> withdraw() {
        var status = deliveryOnboardingService.withdraw(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(status, "Application withdrawn. You can edit your details and submit again."));
    }
}
