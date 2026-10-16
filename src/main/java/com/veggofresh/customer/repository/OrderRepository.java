package com.veggofresh.customer.repository;

import com.veggofresh.customer.entity.Order;
import com.veggofresh.customer.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByUserId(UUID userId, Pageable pageable);

    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    // REPLACED THIS ROUND -- findByShopId (candidacy-based) is gone entirely. Split
    // into two queries with genuinely different meanings, per the vendor-broadcast
    // redesign: candidacy alone was never the right check for either "should this
    // vendor see it as a pending request" (also needs: still PLACED, not yet accepted
    // by anyone, this shop hasn't already rejected it) or "is this vendor's own order"
    // (needs: THIS shop specifically won the accept race, not just was a candidate).
    @Query("SELECT DISTINCT o FROM Order o JOIN o.candidateVendorIds v WHERE v = :shopId")
    List<Order> findByShopId(@Param("shopId") UUID shopId);

    /**
     * The broadcast inbox — every order still awaiting a decision that this shop is
     * still eligible to act on. Status intentionally passed as a parameter rather than
     * a JPQL enum literal, matching this codebase's existing convention elsewhere.
     */
    @Query("SELECT DISTINCT o FROM Order o JOIN o.candidateVendorIds v " +
           "WHERE v = :shopId AND o.status = :placedStatus AND o.acceptedShopId IS NULL " +
           "AND :shopId NOT MEMBER OF o.rejectedShopIds " +
           "ORDER BY o.createdAt DESC")
    List<Order> findRequestsForShop(@Param("shopId") UUID shopId, @Param("placedStatus") OrderStatus placedStatus);

    /**
     * A shop's real order history — only orders THIS shop actually won the accept
     * race for. Newest first (renamed from findByAcceptedShopId, which had no
     * defined order -- vendors were seeing an unpredictable order, effectively
     * whatever the DB happened to return).
     */
    List<Order> findByAcceptedShopIdOrderByCreatedAtDesc(UUID shopId);

    /**
     * NEW THIS ROUND -- real atomic accept for the vendor-accept race, now recording
     * WHO won directly in the same statement (acceptedShopId) -- previously acceptOrder
     * took no shop id at all, which was the root cause of vendors who lost the race
     * still being able to act on the order afterward. Same one-conditional-UPDATE
     * pattern as Delivery's atomicClaim() and this repository's earlier atomicAccept.
     */
    @Modifying
    @Query("UPDATE Order o SET o.status = :newStatus, o.confirmedAt = CURRENT_TIMESTAMP, o.acceptedShopId = :shopId " +
           "WHERE o.id = :orderId AND o.status = :expectedStatus")
    int atomicAccept(@Param("orderId") UUID orderId, @Param("shopId") UUID shopId,
                      @Param("newStatus") OrderStatus newStatus, @Param("expectedStatus") OrderStatus expectedStatus);

    /**
     * NEW THIS ROUND -- feeds the vendor-accept-timeout sweep (mirrors Delivery's
     * expireStaleAssignments pattern). Orders still PLACED, nobody's accepted, and
     * older than the cutoff the caller computes from PlatformSettingsService
     * .getVendorAcceptTimeoutSeconds().
     */
    List<Order> findByStatusAndAcceptedShopIdIsNullAndCreatedAtBefore(OrderStatus status, java.time.Instant cutoff);

    Page<Order> findByUserIdAndStatus(UUID userId, OrderStatus status, Pageable pageable);

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, java.time.Instant timestamp);

    Page<Order> findByUserIdAndStatusIn(UUID userId, List<OrderStatus> statuses, Pageable pageable);

    long countByUserId(UUID userId);

    /** Count orders accepted (won) by a specific vendor shop. */
    long countByAcceptedShopId(UUID shopId);

    /** Batch order counts grouped by customer userId. */
    @Query("SELECT o.userId, COUNT(o) FROM Order o WHERE o.userId IN :userIds AND o.deletedAt IS NULL GROUP BY o.userId")
    List<Object[]> countGroupedByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    /** Batch order counts grouped by accepted shop ID. */
    @Query("SELECT o.acceptedShopId, COUNT(o) FROM Order o WHERE o.acceptedShopId IN :shopIds AND o.deletedAt IS NULL GROUP BY o.acceptedShopId")
    List<Object[]> countGroupedByAcceptedShopIdIn(@Param("shopIds") Collection<UUID> shopIds);

    // ── Admin reporting queries ─────────────────────────────────────────────

    long countByUserIdAndStatus(UUID userId, OrderStatus status);

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.userId = :userId AND o.deletedAt IS NULL")
    java.math.BigDecimal sumTotalAmountByUserId(@Param("userId") UUID userId);

    @Query("SELECT COUNT(DISTINCT o.userId) FROM Order o WHERE " +
           "YEAR(o.createdAt) = YEAR(CURRENT_TIMESTAMP) AND MONTH(o.createdAt) = MONTH(CURRENT_TIMESTAMP) AND " +
           "o.deletedAt IS NULL")
    long countDistinctActiveCustomersThisMonth();

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.status = 'DELIVERED' AND o.deletedAt IS NULL")
    java.math.BigDecimal sumTotalRevenue();

    long countByStatus(OrderStatus status);

    @Query("SELECT o FROM Order o WHERE o.deletedAt IS NULL ORDER BY o.createdAt DESC")
    Page<Order> findAllOrders(Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
}
