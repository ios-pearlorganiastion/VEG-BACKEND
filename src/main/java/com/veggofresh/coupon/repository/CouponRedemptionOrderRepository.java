package com.veggofresh.coupon.repository;

import com.veggofresh.coupon.entity.CouponRedemptionOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CouponRedemptionOrderRepository extends JpaRepository<CouponRedemptionOrder, UUID> {

    Optional<CouponRedemptionOrder> findByOrderId(UUID orderId);

    /** Shares of a redemption that still stand (their order has not been cancelled). */
    long countByRedemptionIdAndCancelledAtIsNull(UUID redemptionId);
}
