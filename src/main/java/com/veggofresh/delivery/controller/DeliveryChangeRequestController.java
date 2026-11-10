package com.veggofresh.delivery.controller;

import com.veggofresh.delivery.dto.DeliveryChangeOverviewDto;
import com.veggofresh.delivery.dto.DeliveryChangeRequestDto;
import com.veggofresh.delivery.dto.request.BasicInfoRequestDto;
import com.veggofresh.delivery.dto.request.VerificationStep3RequestDto;
import com.veggofresh.delivery.service.DeliveryChangeRequestService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Reviewed changes to verified details, for an APPROVED rider. The rider keeps working on the current details
 * while a request is reviewed; they only change when an admin approves.
 */
@RestController
@RequestMapping("/api/delivery/profile-changes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY')")
public class DeliveryChangeRequestController {

    private final DeliveryChangeRequestService changeRequestService;

    /** Verified details with each section's state (VERIFIED / UNDER_REVIEW / REJECTED), open request and last refusal. */
    @GetMapping
    public ResponseEntity<ApiResponse<DeliveryChangeOverviewDto>> getOverview() {
        var overview = changeRequestService.getOverview(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(overview, "Verified details retrieved successfully"));
    }

    /** All of the rider's change requests, newest first. */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<DeliveryChangeRequestDto>>> getHistory() {
        var history = changeRequestService.getHistory(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(history, "Change requests retrieved successfully"));
    }

    @PostMapping("/basic-info")
    public ResponseEntity<ApiResponse<DeliveryChangeRequestDto>> requestBasicInfo(@Valid @RequestBody BasicInfoRequestDto request) {
        var created = changeRequestService.submitBasicInfo(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(created, "Change sent for review"));
    }

    /** The license photo is always required: it is the evidence for the new number. */
    @PostMapping(value = "/license", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DeliveryChangeRequestDto>> requestLicense(
            @RequestParam String licenseNumber,
            @RequestParam(value = "photo", required = false) MultipartFile photo) {
        var created = changeRequestService.submitLicense(SecurityUtils.getCurrentUserId(), licenseNumber, photo);
        return ResponseEntity.ok(ApiResponse.success(created, "Change sent for review"));
    }

    /** The insurance document is always required. */
    @PostMapping(value = "/vehicle", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DeliveryChangeRequestDto>> requestVehicle(
            @RequestParam String vehicleType,
            @RequestParam String plateNumber,
            @RequestParam String vehicleModel,
            @RequestParam Integer manufactureYear,
            @RequestParam(value = "photo", required = false) MultipartFile insurance) {
        var created = changeRequestService.submitVehicle(SecurityUtils.getCurrentUserId(),
                vehicleType, plateNumber, vehicleModel, manufactureYear, insurance);
        return ResponseEntity.ok(ApiResponse.success(created, "Change sent for review"));
    }

    /** The bank proof is always required. Payouts keep going to the current account until this is approved. */
    @PostMapping(value = "/bank", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DeliveryChangeRequestDto>> requestBank(
            @RequestParam String bankName,
            @RequestParam String accountHolderName,
            @RequestParam String accountNumber,
            @RequestParam String ifscCode,
            @RequestParam boolean agreedToPayoutTerms,
            @RequestParam(value = "photo", required = false) MultipartFile proof) {
        VerificationStep3RequestDto bank = new VerificationStep3RequestDto();
        bank.setBankName(bankName);
        bank.setAccountHolderName(accountHolderName);
        bank.setAccountNumber(accountNumber);
        bank.setIfscCode(ifscCode);
        bank.setAgreedToPayoutTerms(agreedToPayoutTerms);
        var created = changeRequestService.submitBank(SecurityUtils.getCurrentUserId(), bank, proof);
        return ResponseEntity.ok(ApiResponse.success(created, "Change sent for review"));
    }

    /** Takes back a request that is still waiting for an admin. */
    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<ApiResponse<DeliveryChangeRequestDto>> cancel(@PathVariable UUID requestId) {
        var cancelled = changeRequestService.cancel(SecurityUtils.getCurrentUserId(), requestId);
        return ResponseEntity.ok(ApiResponse.success(cancelled, "Change request cancelled"));
    }
}
