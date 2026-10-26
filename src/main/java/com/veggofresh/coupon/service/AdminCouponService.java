package com.veggofresh.coupon.service;

import com.veggofresh.coupon.dto.AdminCouponDto;
import com.veggofresh.coupon.dto.CouponRedemptionRowDto;
import com.veggofresh.coupon.dto.CouponRequestDto;
import org.springframework.data.domain.Page;

import java.util.UUID;

/** Admin coupon management. Every method throws a BusinessException with a readable message on bad input. */
public interface AdminCouponService {

    AdminCouponDto create(CouponRequestDto request);

    /** Full replacement of the rules. The code and the discount type cannot change once the coupon has been used. */
    AdminCouponDto update(UUID id, CouponRequestDto request);

    AdminCouponDto get(UUID id);

    /**
     * @param status LIVE, SCHEDULED, EXPIRED, DISABLED or USED_UP (null/blank = all)
     * @param search part of the code or description (null/blank = all)
     */
    Page<AdminCouponDto> list(String status, String search, int page, int size);

    /** The on/off switch. */
    AdminCouponDto setEnabled(UUID id, boolean enabled);

    /** Only a coupon that was never used can be deleted; otherwise switch it off. */
    void delete(UUID id);

    /**
     * The status (LIVE, SCHEDULED, EXPIRED, DISABLED, USED_UP) of the coupon with this code, or null when no such coupon
     * exists. Used by banners, which may advertise a code.
     */
    String statusOfCode(String code);

    /** Who used the coupon, newest first. */
    Page<CouponRedemptionRowDto> redemptions(UUID id, int page, int size);
}
