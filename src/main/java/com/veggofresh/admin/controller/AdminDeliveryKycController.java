package com.veggofresh.admin.controller;

import com.veggofresh.delivery.dto.DeliveryKycDetailDto;
import com.veggofresh.delivery.dto.DeliveryKycReviewDto;
import com.veggofresh.delivery.service.DeliveryKycService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
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
 * ⚠️ BLOCKING CROSS-MODULE DEPENDENCY — replaces DeliveryTestController's stand-in.
 *
 * Calls com.veggofresh.delivery.service.DeliveryKycService and
 * com.veggofresh.delivery.dto.DeliveryKycReviewDto, NEITHER OF WHICH EXIST YET on
 * Delivery's side. This controller will not compile until Delivery implements them.
 * Required contract:
 *
 * <pre>
 * package com.veggofresh.delivery.service;
 * public interface DeliveryKycService {
 *     Page&lt;DeliveryKycReviewDto&gt; listPendingKyc(Pageable pageable);
 *     DeliveryKycReviewDto getKycDetail(UUID userId);
 *     void approveKyc(UUID userId);
 *     void rejectKyc(UUID userId, String reason);
 * }
 * </pre>
 *
 * DeliveryKycReviewDto (package com.veggofresh.delivery.dto) needs at minimum: userId,
 * fullName, phone, vehicle details (from onboarding step 2), license number,
 * kycStatus, rejectionReason (nullable), submittedAt.
 *
 * See NOTES_ADMIN.md. Will be implemented for real when the Delivery module round happens.
 */
@RestController
@RequestMapping("/api/admin/delivery-partners/kyc")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDeliveryKycController {

    private final DeliveryKycService deliveryKycService;

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<PageResponse<DeliveryKycReviewDto>>> listPending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DeliveryKycReviewDto> result = deliveryKycService.listPendingKyc(PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(result), "Pending delivery partner KYC submissions retrieved successfully"));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<DeliveryKycDetailDto>> getDetail(@PathVariable UUID userId) {
        DeliveryKycDetailDto detail = deliveryKycService.getKycDetail(userId);
        return ResponseEntity.ok(ApiResponse.success(detail, "Delivery partner KYC detail retrieved successfully"));
    }

    @PutMapping("/{userId}/approve")
    public ResponseEntity<ApiResponse<Void>> approve(@PathVariable UUID userId) {
        deliveryKycService.approveKyc(userId);
        return ResponseEntity.ok(ApiResponse.success("Delivery partner KYC approved successfully"));
    }

    @PutMapping("/{userId}/reject")
    public ResponseEntity<ApiResponse<Void>> reject(
            @PathVariable UUID userId,
            @RequestParam String reason) {
        deliveryKycService.rejectKyc(userId, reason);
        return ResponseEntity.ok(ApiResponse.success("Delivery partner KYC rejected"));
    }
}
