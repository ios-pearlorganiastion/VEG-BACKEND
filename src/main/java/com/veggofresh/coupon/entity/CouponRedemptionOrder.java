package com.veggofresh.coupon.entity;

import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The part of a checkout's discount carried by one created order. */
@Entity
@Table(name = "coupon_redemption_orders")
@Getter
@Setter
public class CouponRedemptionOrder extends BaseEntity {

    @Column(name = "redemption_id", nullable = false)
    private UUID redemptionId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount;

    /** Set when the order is cancelled / voided: its share of the discount (and of the budget) is given back. */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}
