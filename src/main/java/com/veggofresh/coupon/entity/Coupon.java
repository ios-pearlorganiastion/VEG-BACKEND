package com.veggofresh.coupon.entity;

import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One admin-managed coupon: the RULES only. Who used it and how much it has given away is derived from
 * {@link CouponRedemption} / {@link CouponRedemptionOrder}, never stored as a counter here.
 */
@Entity
@Table(name = "coupons")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")
public class Coupon extends BaseEntity {

    /** Stored trimmed and UPPER-CASE; unique among non-deleted coupons. */
    @Column(nullable = false, length = 40)
    private String code;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private CouponType discountType;

    /** Percent (1-100) or rupees, depending on {@link #discountType}. */
    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    /** Cap on the discount of one checkout. Required for PERCENT coupons. */
    @Column(name = "max_discount", precision = 10, scale = 2)
    private BigDecimal maxDiscount;

    /** Minimum ITEMS total (delivery and platform fee excluded) of the whole checkout. */
    @Column(name = "min_order_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    /** UTC. Null = already started. */
    @Column(name = "starts_at")
    private Instant startsAt;

    /** UTC, exclusive. Null = never ends. */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CouponAudience audience = CouponAudience.EVERYONE;

    @Column(name = "min_previous_orders", nullable = false)
    private int minPreviousOrders;

    /** Maximum number of uses across all customers. Null = unlimited. */
    @Column(name = "total_usage_limit")
    private Integer totalUsageLimit;

    /** Maximum total rupees the coupon may give away. Null = unlimited. */
    @Column(name = "total_budget", precision = 12, scale = 2)
    private BigDecimal totalBudget;

    @Column(name = "per_customer_limit")
    private Integer perCustomerLimit;

    @Enumerated(EnumType.STRING)
    @Column(name = "per_customer_period", length = 20)
    private CouponPeriod perCustomerPeriod;

    /** False = hidden code (works when typed, never listed). Use it for banner and campaign codes. */
    @Column(nullable = false)
    private boolean listed = true;

    /** The instant kill switch. */
    @Column(nullable = false)
    private boolean enabled = true;
}
