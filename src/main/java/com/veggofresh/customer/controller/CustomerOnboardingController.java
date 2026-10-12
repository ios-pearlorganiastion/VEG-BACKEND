package com.veggofresh.customer.controller;

import com.veggofresh.customer.dto.request.CustomerBasicInfoRequestDto;
import com.veggofresh.customer.dto.response.CustomerOnboardingStatusResponseDto;
import com.veggofresh.customer.dto.response.CustomerProfileResponseDto;
import com.veggofresh.customer.service.CustomerProfileService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Customer Onboarding — the one-time, required step shown right after OTP verification.
 *
 * <pre>
 * GET /api/customer/onboarding/status       — hasBasicInfo + nextAction (BASIC_INFO | HOME)
 * PUT /api/customer/onboarding/basic-info   — submit fullName (required)
 * </pre>
 *
 * <p>Client-side flow (mirrors Delivery/Vendor exactly, minus KYC):
 * <ol>
 *   <li>Phone → OTP verified → {@code User} exists, {@code CustomerProfile} auto-created empty.</li>
 *   <li>Client calls {@code GET /api/customer/onboarding/status}. If {@code nextAction} is
 *       {@code BASIC_INFO}, show the basic-info screen; if {@code HOME}, go straight to home.
 *       Routing decision lives server-side, same as Delivery/Vendor's status endpoint --
 *       the client no longer infers "first time" from a blank fullName on the profile call.</li>
 *   <li>User submits their name here. On success, go to home.</li>
 *   <li>If the app is closed before submitting and reopened, step 2 repeats — the
 *       basic-info screen keeps showing until {@code fullName} is actually saved.</li>
 *   <li>After that, home is always the landing screen; name/email/avatar remain editable
 *       any time via {@code PUT /api/customer/profile}.</li>
 * </ol>
 *
 * <p>Deliberately just this one field — email and avatar are NOT part of onboarding and
 * stay fully optional, set later from the profile screen if the customer chooses to.
 */
@RestController
@RequestMapping("/api/customer/onboarding")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerOnboardingController {

    private final CustomerProfileService customerProfileService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<CustomerOnboardingStatusResponseDto>> getStatus() {
        CustomerOnboardingStatusResponseDto status = customerProfileService.getOnboardingStatus(
                SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(status, "Onboarding status retrieved successfully"));
    }

    @PutMapping("/basic-info")
    public ResponseEntity<ApiResponse<CustomerProfileResponseDto>> submitBasicInfo(
            @Valid @RequestBody CustomerBasicInfoRequestDto request) {
        CustomerProfileResponseDto profile = customerProfileService.submitBasicInfo(
                SecurityUtils.getCurrentUserId(), request.getFullName());
        return ResponseEntity.ok(ApiResponse.success(profile, "Basic info saved successfully"));
    }
}
