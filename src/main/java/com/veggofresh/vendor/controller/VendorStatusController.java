package com.veggofresh.vendor.controller;

import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import com.veggofresh.vendor.service.VendorShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/vendor/status")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VENDOR')")
public class VendorStatusController {

    private final VendorShopService vendorShopService;

    @PutMapping
    public ResponseEntity<ApiResponse<Void>> setShopStatus(@RequestBody Map<String, Boolean> statusMap) {
        Boolean isOnline = statusMap.getOrDefault("isOnline", false);
        vendorShopService.setShopStatus(SecurityUtils.getCurrentUserId(), isOnline);
        return ResponseEntity.ok(ApiResponse.success("Shop status updated successfully"));
    }
}
