package com.veggofresh.coupon.controller;

import com.veggofresh.coupon.dto.AdminCouponDto;
import com.veggofresh.coupon.dto.CouponRedemptionRowDto;
import com.veggofresh.coupon.dto.CouponRequestDto;
import com.veggofresh.coupon.service.AdminCouponService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Admin coupon management. Admin only. */
@RestController
@RequestMapping("/api/admin/coupons")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCouponController {

    private final AdminCouponService couponService;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminCouponDto>> create(@RequestBody CouponRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(couponService.create(request), "Coupon created successfully"));
    }

    /** status = LIVE | SCHEDULED | EXPIRED | DISABLED | USED_UP (omit for all); search matches code or description. */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminCouponDto>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(couponService.list(status, search, page, size)), "Coupons retrieved successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminCouponDto>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(couponService.get(id), "Coupon retrieved successfully"));
    }

    /** Full replacement of the rules (omitted limits mean "no limit"). */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminCouponDto>> update(@PathVariable UUID id,
                                                              @RequestBody CouponRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(couponService.update(id, request), "Coupon updated successfully"));
    }

    /** The on/off switch: PATCH /api/admin/coupons/{id}/status?enabled=false */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<AdminCouponDto>> setStatus(@PathVariable UUID id,
                                                                 @RequestParam boolean enabled) {
        return ResponseEntity.ok(ApiResponse.success(couponService.setEnabled(id, enabled),
                enabled ? "Coupon switched on" : "Coupon switched off"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        couponService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Coupon deleted successfully"));
    }

    @GetMapping("/{id}/redemptions")
    public ResponseEntity<ApiResponse<PageResponse<CouponRedemptionRowDto>>> redemptions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(couponService.redemptions(id, page, size)), "Coupon usage retrieved successfully"));
    }
}
