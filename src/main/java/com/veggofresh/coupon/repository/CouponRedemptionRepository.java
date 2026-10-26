package com.veggofresh.coupon.repository;

import com.veggofresh.coupon.entity.CouponRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    /** Uses of a coupon across all customers (released ones are not uses). */
    @Query("select count(r) from CouponRedemption r where r.couponId = :couponId and r.releasedAt is null")
    long countActiveUses(@Param("couponId") UUID couponId);

    /** Uses by one customer inside [from, to). */
    @Query("select count(r) from CouponRedemption r where r.couponId = :couponId and r.userId = :userId "
            + "and r.releasedAt is null and r.createdAt >= :from and r.createdAt < :to")
    long countActiveUsesByCustomer(@Param("couponId") UUID couponId, @Param("userId") UUID userId,
                                   @Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(distinct r.userId) from CouponRedemption r where r.couponId = :couponId and r.releasedAt is null")
    long countActiveCustomers(@Param("couponId") UUID couponId);

    /** Rupees given away so far = shares of orders that are not cancelled. */
    @Query(value = "select coalesce(sum(o.discount_amount), 0) from coupon_redemption_orders o "
            + "join coupon_redemptions r on r.id = o.redemption_id "
            + "where r.coupon_id = :couponId and o.cancelled_at is null", nativeQuery = true)
    BigDecimal sumBudgetUsed(@Param("couponId") UUID couponId);

    /** Rows of [couponId, activeUses] for every coupon that has any (admin list). */
    @Query("select r.couponId, count(r) from CouponRedemption r where r.releasedAt is null group by r.couponId")
    List<Object[]> activeUsesPerCoupon();

    /** Rows of [couponId, budgetUsed] for every coupon that has given anything away (admin list). */
    @Query(value = "select r.coupon_id, coalesce(sum(o.discount_amount), 0) from coupon_redemption_orders o "
            + "join coupon_redemptions r on r.id = o.redemption_id "
            + "where o.cancelled_at is null group by r.coupon_id", nativeQuery = true)
    List<Object[]> budgetUsedPerCoupon();

    /**
     * Orders this customer has that count as "earlier orders": everything except cancelled ones, so a pending order
     * counts (a customer cannot place two "first" orders) and a cancelled / payment-failed one does not.
     * Native on purpose: the coupon module must not import another module's entity.
     */
    @Query(value = "select count(*) from orders where user_id = :userId and status <> 'CANCELLED' and deleted_at is null",
            nativeQuery = true)
    long countCustomerOrders(@Param("userId") UUID userId);

    /**
     * Admin "who used it" list. Columns: redemption id, user id, phone, total discount, used at, released at,
     * order numbers (comma separated).
     */
    @Query(value = "select r.id, r.user_id, u.phone, r.total_discount, r.created_at, r.released_at, "
            + "coalesce((select string_agg(od.order_number, ', ' order by od.created_at) "
            + "          from coupon_redemption_orders cro join orders od on od.id = cro.order_id "
            + "          where cro.redemption_id = r.id), '') "
            + "from coupon_redemptions r left join users u on u.id = r.user_id "
            + "where r.coupon_id = :couponId order by r.created_at desc limit :lim offset :off", nativeQuery = true)
    List<Object[]> findRedemptionRows(@Param("couponId") UUID couponId, @Param("lim") int limit, @Param("off") long offset);

    /** Every use ever, released ones included: decides whether a coupon may still be deleted / have its code changed. */
    @Query("select count(r) from CouponRedemption r where r.couponId = :couponId")
    long countAllForCoupon(@Param("couponId") UUID couponId);
}
