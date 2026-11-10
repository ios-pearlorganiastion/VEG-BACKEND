package com.veggofresh.admin.controller;

import com.veggofresh.delivery.dto.AdminDeliveryChangeRequestDto;
import com.veggofresh.delivery.entity.DeliveryChangeRequestStatus;
import com.veggofresh.delivery.entity.DeliveryKycSection;
import com.veggofresh.delivery.service.DeliveryChangeRequestService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import com.veggofresh.platform.security.SecurityUtils;
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
 * Admin review of changes that approved riders ask for (name/city, license, vehicle, bank). The rider's live
 * details only change when a request is approved here.
 */
@RestController
@RequestMapping("/api/admin/delivery-partners/change-requests")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDeliveryChangeRequestController {

    private final DeliveryChangeRequestService changeRequestService;

    /**
     * With no filters this is the review queue: PENDING requests, oldest first. Add {@code userId} (and no status)
     * for one rider's full history; add {@code section} to narrow by type.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminDeliveryChangeRequestDto>>> list(
            @RequestParam(required = false) DeliveryChangeRequestStatus status,
            @RequestParam(required = false) DeliveryKycSection section,
            @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AdminDeliveryChangeRequestDto> result = changeRequestService.list(status, section, userId, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(result), "Change requests retrieved successfully"));
    }

    /** One request with old vs new values and both documents. */
    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<AdminDeliveryChangeRequestDto>> detail(@PathVariable UUID requestId) {
        return ResponseEntity.ok(ApiResponse.success(changeRequestService.getDetail(requestId), "Change request retrieved successfully"));
    }

    /** Applies the new values. A bank change is refused (409) while the rider has payouts waiting for approval. */
    @PutMapping("/{requestId}/approve")
    public ResponseEntity<ApiResponse<Void>> approve(@PathVariable UUID requestId) {
        changeRequestService.approve(SecurityUtils.getCurrentUserId(), requestId);
        return ResponseEntity.ok(ApiResponse.success("Change request approved successfully"));
    }

    /** {@code reason} is required (at most 500 characters) and is shown to the rider. */
    @PutMapping("/{requestId}/reject")
    public ResponseEntity<ApiResponse<Void>> reject(@PathVariable UUID requestId, @RequestParam String reason) {
        changeRequestService.reject(SecurityUtils.getCurrentUserId(), requestId, reason);
        return ResponseEntity.ok(ApiResponse.success("Change request rejected"));
    }
}
