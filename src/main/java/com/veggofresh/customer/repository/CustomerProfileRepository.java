package com.veggofresh.customer.repository;

import com.veggofresh.customer.entity.CustomerProfile;
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
public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {
    Optional<CustomerProfile> findByUserId(UUID userId);

    /**
     * Paginated query for Admin Get-All-Customers.
     * Searches across fullName and email stored in the profile table.
     */
    @Query("SELECT cp FROM CustomerProfile cp WHERE " +
           "(:search IS NULL OR :search = '' OR " +
           " LOWER(cp.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(cp.email) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<CustomerProfile> findWithFilters(
            @Param("search") String search,
            Pageable pageable);

    /**
     * Non-paginated version for PDF export — caller applies a size limit via Pageable.
     */
    @Query("SELECT cp FROM CustomerProfile cp WHERE " +
           "(:search IS NULL OR :search = '' OR " +
           " LOWER(cp.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(cp.email) LIKE LOWER(CONCAT('%', :search, '%')))")
    List<CustomerProfile> findWithFiltersNoPage(
            @Param("search") String search,
            Pageable pageable);
}
