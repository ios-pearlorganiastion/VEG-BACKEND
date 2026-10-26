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

/**
 * One USE of a coupon = one checkout (of one, several or all carts). It counts as a use while
 * {@link #releasedAt} is null; it is released (the use is given back) when every order that carried part of its
 * discount has been cancelled.
 */
@Entity
@Table(name = "coupon_redemptions")
@Getter
@Setter
public class CouponRedemption extends BaseEntity {

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The whole discount of the checkout (the sum of the order shares). */
    @Column(name = "total_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalDiscount;

    @Column(name = "released_at")
    private Instant releasedAt;
}
