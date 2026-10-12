package com.veggofresh.vendor.controller;

import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import com.veggofresh.vendor.service.VendorReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/vendor/reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VENDOR')")
public class VendorReportController {

    private final VendorReportService vendorReportService;

    @GetMapping("/sales")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSales() {
        return ResponseEntity.ok(ApiResponse.success(vendorReportService.getSalesReports(SecurityUtils.getCurrentUserId()), "Sales report retrieved"));
    }

    @GetMapping("/earnings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEarnings() {
        return ResponseEntity.ok(ApiResponse.success(vendorReportService.getEarningsReports(SecurityUtils.getCurrentUserId()), "Earnings report retrieved"));
    }
}
