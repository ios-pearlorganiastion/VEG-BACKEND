package com.veggofresh.payment.repository;

import com.veggofresh.payment.entity.PayoutRequest;
import com.veggofresh.payment.entity.PayoutRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PayoutRequestRepository extends JpaRepository<PayoutRequest, UUID> {

    Page<PayoutRequest> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<PayoutRequest> findByStatusOrderByCreatedAtDesc(PayoutRequestStatus status, Pageable pageable);

    java.util.Optional<PayoutRequest> findByRazorpayPayoutId(String razorpayPayoutId);

    /**
     * How many of a user's payout requests are in the given status. Used to refuse a bank-account change while
     * payouts are still PENDING: those read the bank details only when an admin approves them, so changing the
     * account in between would send them to the new account.
     */
    long countByUserIdAndStatus(UUID userId, PayoutRequestStatus status);
}
