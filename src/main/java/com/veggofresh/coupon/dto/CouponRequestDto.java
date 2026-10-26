package com.veggofresh.coupon.dto;

import com.veggofresh.coupon.entity.CouponAudience;
import com.veggofresh.coupon.entity.CouponPeriod;
import com.veggofresh.coupon.entity.CouponType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Create / update body for a coupon. On update this is a FULL replacement of the rules (an omitted limit means
 * "no limit"); only {@code code}, {@code listed} and {@code enabled} keep their current value when omitted.
 *
 * <p>{@code startsAt} / {@code endsAt}: ISO date-time. Without an offset ("2026-11-01T00:00:00") it is read as Indian
 * time (IST); with an offset or "Z" it is taken as given.
 */
@Getter
@Setter
public class CouponRequestDto {
    private String code;
    private String description;
    private CouponType discountType;
    private BigDecimal discountValue;
    /** Required for PERCENT coupons. Ignored for FLAT. */
    private BigDecimal maxDiscount;
    /** Minimum items total of the whole checkout. Default 0. */
    private BigDecimal minOrderAmount;
    private String startsAt;
    private String endsAt;
    /** Default EVERYONE. */
    private CouponAudience audience;
    /** RETURNING_CUSTOMERS only: how many earlier orders are needed (default 1). */
    private Integer minPreviousOrders;
    private Integer totalUsageLimit;
    private BigDecimal totalBudget;
    /** Both or neither of the next two. */
    private Integer perCustomerLimit;
    private CouponPeriod perCustomerPeriod;
    /** Shown in the customers' offers list. Default true on create. */
    private Boolean listed;
    /** Default true on create. */
    private Boolean enabled;
}
