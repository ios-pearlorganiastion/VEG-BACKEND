package com.veggofresh.coupon.repository;

import com.veggofresh.coupon.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    /** Case-insensitive lookup among non-deleted coupons (the entity's {@code @Where} hides deleted ones). */
    @Query("select c from Coupon c where upper(c.code) = upper(:code)")
    Optional<Coupon> findByCode(@Param("code") String code);

    /**
     * Same, but takes a row lock held until the surrounding transaction ends. Place-order uses this so two
     * checkouts can never both take the last use / the last of the budget.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where upper(c.code) = upper(:code)")
    Optional<Coupon> findByCodeForUpdate(@Param("code") String code);

    @Query("select count(c) from Coupon c where upper(c.code) = upper(:code)")
    long countByCode(@Param("code") String code);

    @Query("select c from Coupon c order by c.createdAt desc")
    List<Coupon> findAllNewestFirst();

    /** What the customers' offers list may show: switched on, listed, and not past its end. */
    @Query("select c from Coupon c where c.enabled = true and c.listed = true "
            + "and (c.endsAt is null or c.endsAt > :now) order by c.createdAt desc")
    List<Coupon> findListedOffers(@Param("now") Instant now);
}
