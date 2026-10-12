package com.veggofresh.auth.repository;

import com.veggofresh.auth.entity.User;
import com.veggofresh.auth.entity.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    /**
     * The ACTIVE account of this phone number in one specific role.
     * A phone number can own up to three accounts (CUSTOMER, VENDOR, DELIVERY) but never two of the same
     * role (migration V164), so a phone-only lookup would be ambiguous and has been removed on purpose.
     */
    Optional<User> findByPhoneAndRole(String phone, UserRole role);
    Optional<User> findByEmail(String email);

    // ── Admin customer management queries ───────────────────────────────────

    /** Paginated list of all customers */
    Page<User> findByRole(UserRole role, Pageable pageable);

    /** Search customers by name/email/phone — used for ledger search bar */
    @Query("SELECT u FROM User u WHERE u.role = :role AND " +
           "(LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           " LOWER(u.phone) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<User> searchByRoleAndQuery(@Param("role") UserRole role,
                                    @Param("query") String query,
                                    Pageable pageable);

    /** Count by role */
    long countByRole(UserRole role);

    /** Count blocked accounts for a given role */
    long countByRoleAndIsBlocked(UserRole role, boolean isBlocked);

    /** All blocked customers — for moderation ledger */
    Page<User> findByRoleAndIsBlocked(UserRole role, boolean isBlocked, Pageable pageable);
}
