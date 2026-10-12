package com.veggofresh.delivery.service;

import com.veggofresh.delivery.dto.DeliveryKycDetailDto;
import com.veggofresh.delivery.dto.DeliveryKycReviewDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Real implementation this round -- resolves the blocking cross-module dependency
 * Admin's AdminDeliveryKycController was built against (see Admin's NOTES_ADMIN.md).
 * Replaces DeliveryTestController's approve-kyc stand-in as the real review path.
 * DeliveryTestController is left in place, not deleted, until this is confirmed working
 * end-to-end against a real Admin UI.
 */
public interface DeliveryKycService {
    Page<DeliveryKycReviewDto> listPendingKyc(Pageable pageable);
    /** Full onboarding data (profile, vehicle, bank, documents) for the admin review screen. */
    DeliveryKycDetailDto getKycDetail(UUID userId);
    void approveKyc(UUID userId);
    void rejectKyc(UUID userId, String reason);
}
