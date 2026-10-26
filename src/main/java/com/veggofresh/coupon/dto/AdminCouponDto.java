package com.veggofresh.coupon.dto;

import com.veggofresh.coupon.entity.CouponAudience;
import com.veggofresh.coupon.entity.CouponPeriod;
import com.veggofresh.coupon.entity.CouponType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A coupon as the admin sees it: the rules plus how it is doing. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCouponDto {
    private UUID id;
    private String code;
    private String description;
    private CouponType discountType;
    private BigDecimal discountValue;
    private BigDecimal maxDiscount;
    private BigDecimal minOrderAmount;
    /** UTC. */
    private Instant startsAt;
    private Instant endsAt;
    /** The same moments in Indian time, "yyyy-MM-dd'T'HH:mm:ss" (convenient for the admin form). */
    private String startsAtIst;
    private String endsAtIst;
    private CouponAudience audience;
    private int minPreviousOrders;
    private Integer totalUsageLimit;
    private BigDecimal totalBudget;
    private Integer perCustomerLimit;
    private CouponPeriod perCustomerPeriod;
    private boolean listed;
    private boolean enabled;

    /** LIVE, SCHEDULED, EXPIRED, DISABLED or USED_UP. */
    private String status;
    /** Uses so far (a checkout of several carts is one use; released uses are not counted). */
    private long usesCount;
    /** Null when the usage limit is unlimited. */
    private Long usesLeft;
    /** Rupees given away so far (cancelled orders excluded). */
    private BigDecimal budgetUsed;
    /** Null when the budget is unlimited. */
    private BigDecimal budgetLeft;
    private long customersCount;

    private Instant createdAt;
    private Instant updatedAt;
}
