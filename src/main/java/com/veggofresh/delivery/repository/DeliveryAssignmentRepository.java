package com.veggofresh.delivery.repository;

import com.veggofresh.delivery.entity.DeliveryAssignment;
import com.veggofresh.delivery.entity.DeliveryAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryAssignmentRepository extends JpaRepository<DeliveryAssignment, UUID> {
    Optional<DeliveryAssignment> findByOrderIdAndStatusIn(UUID orderId, List<DeliveryAssignmentStatus> statuses);
    List<DeliveryAssignment> findByDeliveryPartnerUserIdAndStatus(UUID deliveryPartnerUserId, DeliveryAssignmentStatus status);
    List<DeliveryAssignment> findByStatusAndExpiresAtBefore(DeliveryAssignmentStatus status, Instant instant);
    List<DeliveryAssignment> findByOrderId(UUID orderId);

    /**
     * NEW -- real atomic accept. A single conditional UPDATE, not a read-then-write:
     * only succeeds (returns 1) if the row is STILL in expectedStatus at the moment the
     * UPDATE runs. If two delivery partners race to accept the same assignment, exactly
     * one UPDATE affects a row; the other affects zero rows and the caller turns that
     * into a clean "someone else already accepted" response instead of silently
     * corrupting state or relying on catching an OptimisticLockException after the fact.
     */
    @Modifying
    @Query("UPDATE DeliveryAssignment a SET a.status = :newStatus, a.deliveryPartnerUserId = :partnerId " +
           "WHERE a.id = :id AND a.status = :expectedStatus")
    int atomicClaim(@Param("id") UUID id, @Param("partnerId") UUID partnerId,
                     @Param("newStatus") DeliveryAssignmentStatus newStatus,
                     @Param("expectedStatus") DeliveryAssignmentStatus expectedStatus);

    /** NEW -- how many rounds (PENDING assignment rows) this order has gone through so far, across all re-broadcasts. */
    long countByOrderId(UUID orderId);

    /** Count deliveries completed by a specific partner — used in admin count summaries. */
    long countByDeliveryPartnerUserIdAndStatus(UUID deliveryPartnerUserId, DeliveryAssignmentStatus status);

    /** Batch completed delivery counts grouped by partner user ID. */
    @Query("SELECT a.deliveryPartnerUserId, COUNT(a) FROM DeliveryAssignment a WHERE a.deliveryPartnerUserId IN :partnerUserIds AND a.status = :status GROUP BY a.deliveryPartnerUserId")
    List<Object[]> countGroupedByPartnerUserIdInAndStatus(@Param("partnerUserIds") Collection<UUID> partnerUserIds, @Param("status") DeliveryAssignmentStatus status);

    /** NEW -- the very first round's row, whose createdAt anchors the "total elapsed time across all rounds" cap. */
    Optional<DeliveryAssignment> findFirstByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
