package com.veggofresh.coupon.entity;

import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * The coupon a customer is "holding": typed in the cart (or on the checkout screen) and used at the next checkout.
 * It belongs to the customer, not to a cart; how much it is worth is only decided when the carts being paid are
 * known. One row per customer.
 */
@Entity
@Table(name = "customer_coupon_selections")
@Getter
@Setter
public class CustomerCouponSelection extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;
}
