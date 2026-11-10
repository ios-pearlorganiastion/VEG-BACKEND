package com.veggofresh.delivery.repository;

import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeliveryPartnerProfileRepository extends JpaRepository<DeliveryPartnerProfile, UUID> {
    Optional<DeliveryPartnerProfile> findByUserId(UUID userId);
    List<DeliveryPartnerProfile> findByOnlineTrueAndKycStatus(DeliveryKycStatus kycStatus);

    /**
     * "Awaiting review" means kycStatus PENDING AND the rider actually submitted (applicationSubmittedAt set by the
     * final onboarding step). PENDING alone is not enough -- it is the default from the first save, so it also
     * matches riders still filling in their application. Powers the admin's KYC review queue.
     */
    Page<DeliveryPartnerProfile> findByKycStatusAndApplicationSubmittedAtIsNotNull(DeliveryKycStatus kycStatus, Pageable pageable);

    // ── Admin Get-All-Delivery-Partners with full filters ────────────────────

    /**
     * Paginated query with optional filters: search (name/email/phone/city/vehicle),
     * kycStatus, submitted (true = application submitted, false = still a draft, null = either), isOnline.
     * Sorting is handled by Pageable.
     * Soft-deleted records excluded by @Where on entity.
     */
    @Query("SELECT d FROM DeliveryPartnerProfile d WHERE " +
           "(:search IS NULL OR :search = '' OR " +
           " LOWER(d.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(d.email) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(d.vehicleType) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(d.cityOfOperation) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(d.plateNumber) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
           "(:kycStatus IS NULL OR d.kycStatus = :kycStatus) AND " +
           "(:submitted IS NULL OR " +
           " (:submitted = true AND d.applicationSubmittedAt IS NOT NULL) OR " +
           " (:submitted = false AND d.applicationSubmittedAt IS NULL)) AND " +
           "(:isOnline IS NULL OR d.online = :isOnline)")
    Page<DeliveryPartnerProfile> findWithFilters(
            @Param("search") String search,
            @Param("kycStatus") DeliveryKycStatus kycStatus,
            @Param("submitted") Boolean submitted,
            @Param("isOnline") Boolean isOnline,
            Pageable pageable);

    /**
     * Non-paginated version for PDF export — caller applies a size limit via Pageable.
     */
    @Query("SELECT d FROM DeliveryPartnerProfile d WHERE " +
           "(:search IS NULL OR :search = '' OR " +
           " LOWER(d.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(d.email) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(d.vehicleType) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(d.cityOfOperation) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(d.plateNumber) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
           "(:kycStatus IS NULL OR d.kycStatus = :kycStatus) AND " +
           "(:submitted IS NULL OR " +
           " (:submitted = true AND d.applicationSubmittedAt IS NOT NULL) OR " +
           " (:submitted = false AND d.applicationSubmittedAt IS NULL)) AND " +
           "(:isOnline IS NULL OR d.online = :isOnline)")
    List<DeliveryPartnerProfile> findWithFiltersNoPage(
            @Param("search") String search,
            @Param("kycStatus") DeliveryKycStatus kycStatus,
            @Param("submitted") Boolean submitted,
            @Param("isOnline") Boolean isOnline,
            Pageable pageable);
}

