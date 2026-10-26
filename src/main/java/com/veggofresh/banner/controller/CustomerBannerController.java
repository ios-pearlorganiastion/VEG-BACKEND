package com.veggofresh.banner.controller;

import com.veggofresh.banner.dto.CustomerBannerDto;
import com.veggofresh.banner.service.CustomerBannerService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The hero banners of the customer app. Same URL as before (it used to return two hard-coded fake banners from
 * CustomerBrowseController); the fields id, title, subtitle and imageUrl are unchanged.
 */
@RestController
@RequestMapping("/api/customer/banners")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerBannerController {

    private final CustomerBannerService customerBannerService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CustomerBannerDto>>> getBanners() {
        return ResponseEntity.ok(ApiResponse.success(
                customerBannerService.getBanners(SecurityUtils.getCurrentUserId()),
                "Hero banners retrieved successfully"));
    }
}
