package com.veggofresh.customer.controller;

import com.veggofresh.customer.dto.request.AddressRequestDto;
import com.veggofresh.customer.dto.request.CustomerProfileUpdateRequestDto;
import com.veggofresh.customer.dto.response.AddressResponseDto;
import com.veggofresh.customer.dto.response.CustomerProfileResponseDto;
import com.veggofresh.customer.dto.response.CustomerProfileSummaryDto;
import com.veggofresh.customer.service.AddressService;
import com.veggofresh.customer.service.CustomerProfileService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Customer Profile & Address Management
 *
 * <pre>
 * GET  /api/customer/profile          — fetch (or auto-create) profile
 * PUT  /api/customer/profile          — update fullName, email, and/or avatar in one
 *                                        multipart/form-data call (PATCH semantics —
 *                                        only the parts you send are changed)
 * GET  /api/customer/profile/summary  — aggregated stats card
 *
 * GET    /api/customer/addresses      — list addresses
 * POST   /api/customer/addresses      — add address
 * PUT    /api/customer/addresses/{id} — update address
 * DELETE /api/customer/addresses/{id} — soft-delete address
 * </pre>
 *
 * The one-time name-only onboarding step lives separately in
 * {@link CustomerOnboardingController} — see {@code PUT /api/customer/onboarding/basic-info}.
 */
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerProfileController {

    private final CustomerProfileService customerProfileService;
    private final AddressService addressService;

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<CustomerProfileResponseDto>> getProfile() {
        CustomerProfileResponseDto profile =
                customerProfileService.getOrCreateProfile(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(profile, "Customer profile retrieved successfully"));
    }

    /**
     * Updates fullName, email, and/or avatar in a single call. Send this as
     * {@code multipart/form-data} with any combination of the {@code fullName} (text),
     * {@code email} (text), and {@code avatar} (file) parts — anything you omit is left
     * unchanged. Sending just {@code avatar} updates only the avatar, exactly like
     * sending just {@code fullName} updates only the name.
     */
    @PutMapping(value = "/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CustomerProfileResponseDto>> updateProfile(
            @Valid @ModelAttribute CustomerProfileUpdateRequestDto request) {
        CustomerProfileResponseDto profile =
                customerProfileService.updateProfile(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(profile, "Customer profile updated successfully"));
    }

    @GetMapping("/profile/summary")
    public ResponseEntity<ApiResponse<CustomerProfileSummaryDto>> getProfileSummary() {
        CustomerProfileSummaryDto summary =
                customerProfileService.getProfileSummary(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(summary, "Customer profile summary retrieved successfully"));
    }

    @GetMapping("/addresses")
    public ResponseEntity<ApiResponse<List<AddressResponseDto>>> getAddresses() {
        List<AddressResponseDto> addresses = addressService.getAddresses(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(addresses, "Addresses retrieved successfully"));
    }

    @PostMapping("/addresses")
    public ResponseEntity<ApiResponse<AddressResponseDto>> addAddress(
            @Valid @RequestBody AddressRequestDto request) {
        AddressResponseDto address = addressService.addAddress(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(address, "Address added successfully"));
    }

    @PutMapping("/addresses/{id}")
    public ResponseEntity<ApiResponse<AddressResponseDto>> updateAddress(
            @PathVariable UUID id,
            @Valid @RequestBody AddressRequestDto request) {
        AddressResponseDto address = addressService.updateAddress(SecurityUtils.getCurrentUserId(), id, request);
        return ResponseEntity.ok(ApiResponse.success(address, "Address updated successfully"));
    }

    @DeleteMapping("/addresses/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAddress(@PathVariable UUID id) {
        addressService.deleteAddress(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(ApiResponse.success("Address deleted successfully"));
    }
}
