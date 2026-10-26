package com.veggofresh.coupon.controller;

import com.veggofresh.coupon.dto.CustomerCouponDto;
import com.veggofresh.coupon.service.CouponCheckoutService;
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
 * The customer's offers list. Applying and removing a coupon keep their existing URLs
 * (POST / DELETE /api/customer/carts/{cartId}/promo-code).
 */
@RestController
@RequestMapping("/api/customer/coupons")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerCouponController {

    private final CouponCheckoutService couponCheckoutService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CustomerCouponDto>>> offers() {
        return ResponseEntity.ok(ApiResponse.success(
                couponCheckoutService.listOffers(SecurityUtils.getCurrentUserId()), "Coupons retrieved successfully"));
    }
}
