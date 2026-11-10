package com.veggofresh.delivery.repository;

import com.veggofresh.delivery.entity.DeliveryChangeRequest;
import com.veggofresh.delivery.entity.DeliveryChangeRequestStatus;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryChangeRequestRepository extends JpaRepository<DeliveryChangeRequest, UUID> {

    /** The rider's open request for a section, if any (there is at most one). */
    Optional<DeliveryChangeRequest> findByDeliveryPartnerUserIdAndSectionAndStatus(
            UUID deliveryPartnerUserId, DeliveryKycSection section, DeliveryChangeRequestStatus status);

    /** All of a rider's requests, newest first. */
    List<DeliveryChangeRequest> findByDeliveryPartnerUserIdOrderByCreatedAtDescIdDesc(UUID deliveryPartnerUserId);

    /** Admin list across all riders. {@code status} and {@code section} are optional. */
    @Query("SELECT c FROM DeliveryChangeRequest c WHERE " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:section IS NULL OR c.section = :section)")
    Page<DeliveryChangeRequest> findWithFilters(
            @Param("status") DeliveryChangeRequestStatus status,
            @Param("section") DeliveryKycSection section,
            Pageable pageable);

    /** Admin list for one rider (the userId is never null here). */
    @Query("SELECT c FROM DeliveryChangeRequest c WHERE c.deliveryPartnerUserId = :userId AND " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:section IS NULL OR c.section = :section)")
    Page<DeliveryChangeRequest> findForPartnerWithFilters(
            @Param("userId") UUID userId,
            @Param("status") DeliveryChangeRequestStatus status,
            @Param("section") DeliveryKycSection section,
            Pageable pageable);
}
