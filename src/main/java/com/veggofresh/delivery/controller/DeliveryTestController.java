package com.veggofresh.delivery.controller;

import com.veggofresh.delivery.dto.response.DeliveryProfileResponseDto;
import com.veggofresh.delivery.service.DeliveryDispatchService;
import com.veggofresh.delivery.service.DeliveryProfileService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * TEST-ONLY CONTROLLER — NOT PART OF THE MODULE SPEC.
 *
 * Exists solely to make the Delivery module's happy-path testable via Postman before
 * two upstream dependencies are real:
 *   1. Admin module's KYC-approval endpoint (PUT /api/admin/delivery-partners/{id}/approve)
 *      does not exist as working code yet.
 *   2. No module currently calls DeliveryDispatchService.dispatchOrder(...) on order
 *      confirmation, so there is no other way to create a PENDING DeliveryAssignment.
 *
 * DELETE THIS FILE once (1) Admin module's real approval flow exists, and (2) Customer
 * or Vendor module wires the real dispatch trigger on order confirmation. Do not merge
 * this to a production branch as-is — /test/dispatch has no role restriction.
 */
// Self-approval for local testing only. Never available in production: a rider must not be able to approve
// their own application. Active in every profile except "prod".
@Profile("!prod")
@RestController
@RequestMapping("/api/delivery/test")
@RequiredArgsConstructor
public class DeliveryTestController {

    private final DeliveryProfileService deliveryProfileService;
    private final DeliveryDispatchService deliveryDispatchService;

    @PostMapping("/approve-kyc")
    @PreAuthorize("hasRole('DELIVERY')")
    public ResponseEntity<ApiResponse<DeliveryProfileResponseDto>> approveKyc() {
        var profile = deliveryProfileService.approveKycForTesting(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(profile, "[TEST-ONLY] KYC force-approved"));
    }

    @PostMapping("/dispatch")
    public ResponseEntity<ApiResponse<Void>> dispatch(
            @RequestParam UUID orderId,
            @RequestParam(required = false) UUID customerUserId,
            @RequestParam(required = false) UUID shopOwnerUserId,
            @RequestParam(required = false, defaultValue = "Test Shop") String shopName,
            @RequestParam(required = false, defaultValue = "Test Shop Address") String shopAddress,
            @RequestParam double pickupLat,
            @RequestParam double pickupLng,
            @RequestParam double dropLat,
            @RequestParam double dropLng,
            @RequestParam(required = false, defaultValue = "Test Drop Address") String dropAddress) {
        deliveryDispatchService.dispatchOrder(orderId, customerUserId, shopOwnerUserId, shopName, shopAddress,
                pickupLat, pickupLng, dropLat, dropLng, dropAddress);
        return ResponseEntity.ok(ApiResponse.success("[TEST-ONLY] Assignment dispatched for order " + orderId));
    }
}
