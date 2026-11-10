package com.veggofresh.delivery.controller;

import com.veggofresh.delivery.dto.request.DeliveryLocationStatusRequestDto;
import com.veggofresh.delivery.dto.request.DeliveryProfileRequestDto;
import com.veggofresh.delivery.dto.response.DeliveryProfileResponseDto;
import com.veggofresh.delivery.dto.response.DeliveryProfileStatsResponseDto;
import com.veggofresh.delivery.service.DeliveryProfileService;
import com.veggofresh.delivery.service.DeliveryStatsService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY')")
public class DeliveryProfileController {

    private final DeliveryProfileService deliveryProfileService;
    private final DeliveryStatsService deliveryStatsService;

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<DeliveryProfileResponseDto>> getProfile() {
        var profile = deliveryProfileService.getOrCreateProfile(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(profile, "Profile retrieved successfully"));
    }

    @GetMapping("/profile/stats")
    public ResponseEntity<ApiResponse<DeliveryProfileStatsResponseDto>> getProfileStats() {
        var stats = deliveryStatsService.getStats(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(stats, "Profile stats retrieved successfully"));
    }

    @PostMapping("/profile")
    public ResponseEntity<ApiResponse<DeliveryProfileResponseDto>> updateProfile(
            @Valid @RequestBody DeliveryProfileRequestDto request) {
        var profile = deliveryProfileService.updateProfile(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(profile, "Profile updated successfully"));
    }

    @PutMapping("/status")
    public ResponseEntity<ApiResponse<DeliveryProfileResponseDto>> updateStatus(
            @Valid @RequestBody DeliveryLocationStatusRequestDto request) {
        var profile = deliveryProfileService.updateStatus(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(profile, "Status updated successfully"));
    }
}
