package com.veggofresh.coupon.repository;

import com.veggofresh.coupon.entity.CustomerCouponSelection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CustomerCouponSelectionRepository extends JpaRepository<CustomerCouponSelection, UUID> {

    Optional<CustomerCouponSelection> findByUserId(UUID userId);

    @Modifying
    @Query("delete from CustomerCouponSelection s where s.couponId = :couponId")
    int deleteByCoupon(@Param("couponId") UUID couponId);
}
