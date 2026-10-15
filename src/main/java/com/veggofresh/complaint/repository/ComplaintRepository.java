package com.veggofresh.complaint.repository;

import com.veggofresh.auth.entity.UserRole;
import com.veggofresh.complaint.entity.Complaint;
import com.veggofresh.complaint.entity.ComplaintStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    /**
     * User inbox: Fetch complaints filed by this user, ordered by createdAt DESC by default.
     */
    Page<Complaint> findByUserId(UUID userId, Pageable pageable);

    /**
     * User inbox filtered by status.
     */
    Page<Complaint> findByUserIdAndStatus(UUID userId, ComplaintStatus status, Pageable pageable);

    /**
     * Look up complaint by unique ticket number.
     */
    Optional<Complaint> findByTicketNumber(String ticketNumber);

    /**
     * Look up complaint ensuring it belongs to the given user.
     */
    Optional<Complaint> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Admin view with optional filters: status, userRole, and keyword search.
     */
    @Query("SELECT c FROM Complaint c WHERE " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:userRole IS NULL OR c.userRole = :userRole) AND " +
           "(:search IS NULL OR :search = '' OR " +
           " LOWER(c.ticketNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(c.title) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(c.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Complaint> findByFilters(
            @Param("status") ComplaintStatus status,
            @Param("userRole") UserRole userRole,
            @Param("search") String search,
            Pageable pageable);

    /**
     * Counts for statistics dashboard.
     */
    long countByStatus(ComplaintStatus status);

    long countByUserRole(UserRole userRole);
}
