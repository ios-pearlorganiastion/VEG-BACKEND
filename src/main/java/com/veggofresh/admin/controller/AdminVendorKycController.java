package com.veggofresh.admin.controller;

import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import com.veggofresh.vendor.dto.VendorKycDetailDto;
import com.veggofresh.vendor.dto.VendorKycReviewDto;
import com.veggofresh.vendor.service.VendorKycService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ⚠️ BLOCKING CROSS-MODULE DEPENDENCY — replaces VendorTestController's stand-in.
 *
 * Calls com.veggofresh.vendor.service.VendorKycService and
 * com.veggofresh.vendor.dto.VendorKycReviewDto, NEITHER OF WHICH EXIST YET on Vendor's
 * side. This controller will not compile until Vendor implements them. Required contract:
 *
 * <pre>
 * package com.veggofresh.vendor.service;
 * public interface VendorKycService {
 *     Page&lt;VendorKycReviewDto&gt; listPendingKyc(Pageable pageable);
 *     VendorKycReviewDto getKycDetail(UUID shopId);
 *     void approveKyc(UUID shopId);
 *     void rejectKyc(UUID shopId, String reason);
 * }
 * </pre>
 *
 * VendorKycReviewDto (package com.veggofresh.vendor.dto) needs at minimum: shopId,
 * ownerUserId, businessName, ownerFullName, businessPhone, email, kycStatus,
 * rejectionReason (nullable), submitted document summaries, submittedAt.
 *
 * "Pending" = shops with kycStatus SUBMITTED (awaiting review) — not PENDING (onboarding
 * incomplete, nothing to review yet) and not already APPROVED/REJECTED.
 *
 * See NOTES_ADMIN.md. Will be implemented for real when the Vendor module round happens.
 */
@RestController
@RequestMapping("/api/admin/vendors/kyc")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminVendorKycController {

    private final VendorKycService vendorKycService;

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<PageResponse<VendorKycReviewDto>>> listPending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<VendorKycReviewDto> result = vendorKycService.listPendingKyc(PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(result), "Pending vendor KYC submissions retrieved successfully"));
    }

    @GetMapping("/{shopId}")
    public ResponseEntity<ApiResponse<VendorKycDetailDto>> getDetail(@PathVariable UUID shopId) {
        VendorKycDetailDto detail = vendorKycService.getKycDetail(shopId);
        return ResponseEntity.ok(ApiResponse.success(detail, "Vendor KYC detail retrieved successfully"));
    }

    @PutMapping("/{shopId}/approve")
    public ResponseEntity<ApiResponse<Void>> approve(@PathVariable UUID shopId) {
        vendorKycService.approveKyc(shopId);
        return ResponseEntity.ok(ApiResponse.success("Vendor KYC approved successfully"));
    }

    @PutMapping("/{shopId}/reject")
    public ResponseEntity<ApiResponse<Void>> reject(
            @PathVariable UUID shopId,
            @RequestParam String reason) {
        vendorKycService.rejectKyc(shopId, reason);
        return ResponseEntity.ok(ApiResponse.success("Vendor KYC rejected"));
    }
}
