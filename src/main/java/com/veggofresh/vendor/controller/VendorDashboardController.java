package com.veggofresh.vendor.controller;

import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import com.veggofresh.vendor.dto.response.DashboardSummaryResponseDto;
import com.veggofresh.vendor.service.VendorDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/vendor/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VENDOR')")
public class VendorDashboardController {

    private final VendorDashboardService vendorDashboardService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponseDto>> getSummary() {
        return ResponseEntity.ok(ApiResponse.success(
                vendorDashboardService.getDashboardSummary(SecurityUtils.getCurrentUserId()),
                "Dashboard summary retrieved successfully"
        ));
    }
}
