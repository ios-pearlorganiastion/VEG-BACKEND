package com.veggofresh.delivery.repository;

import com.veggofresh.delivery.entity.DeliveryKycReviewEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryKycReviewHistoryRepository extends JpaRepository<DeliveryKycReviewEntry, UUID> {

    /** Oldest first, so the history reads as a timeline. */
    List<DeliveryKycReviewEntry> findByDeliveryPartnerUserIdOrderByCreatedAtAscIdAsc(UUID deliveryPartnerUserId);
}
