package com.veggofresh.vendor.service;

import com.veggofresh.vendor.dto.VendorKycDetailDto;
import com.veggofresh.vendor.dto.VendorKycReviewDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Real implementation -- resolves the blocking cross-module dependency Admin's
 * AdminVendorKycController was built against (see Admin's NOTES_ADMIN.md). This was
 * meant to be built "when the Vendor round happens" -- it took two Vendor rounds
 * (catalog pivot, then delivery-dispatch) before this actually got built; flagging that
 * gap plainly rather than pretending it was planned this way. Replaces
 * VendorTestController's approve-kyc stand-in as the real review path.
 * VendorTestController is left in place, not deleted, until this is confirmed working
 * end-to-end against a real Admin UI.
 */
public interface VendorKycService {
    Page<VendorKycReviewDto> listPendingKyc(Pageable pageable);
    /** Full onboarding data (details, address, bank, documents) for the admin review screen. */
    VendorKycDetailDto getKycDetail(UUID shopId);
    void approveKyc(UUID shopId);
    void rejectKyc(UUID shopId, String reason);
}
