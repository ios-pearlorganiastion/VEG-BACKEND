package com.veggofresh.coupon.dto;

import com.veggofresh.coupon.entity.CouponAudience;
import com.veggofresh.coupon.entity.CouponType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** A coupon in the customer's offers list. Never contains vendor prices, margins, budgets or usage counts. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerCouponDto {
    private String code;
    private String description;
    private CouponType discountType;
    private BigDecimal discountValue;
    private BigDecimal maxDiscount;
    private BigDecimal minOrderAmount;
    private Instant validFrom;
    private Instant validUntil;
    private CouponAudience audience;
    /** Can this customer apply it right now (the amount-independent rules)? */
    private boolean usable;
    /** When not usable: a stable code (COUPON_EXPIRED, COUPON_NOT_FOR_YOU, ...) and a readable reason. */
    private String reasonCode;
    private String reason;
    /** True when this is the coupon the customer is currently holding. */
    private boolean selected;
}
