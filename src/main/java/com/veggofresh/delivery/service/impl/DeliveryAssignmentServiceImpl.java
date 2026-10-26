package com.veggofresh.delivery.service.impl;

import com.veggofresh.admin.service.PlatformSettingsService;
import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.customer.service.CustomerOrderService;
import com.veggofresh.delivery.dto.response.DeliveryAssignmentResponseDto;
import com.veggofresh.delivery.dto.response.ProofOfDeliveryResponseDto;
import com.veggofresh.delivery.entity.DeliveryAssignment;
import com.veggofresh.delivery.entity.DeliveryAssignmentStatus;
import com.veggofresh.delivery.entity.DeliveryAssignmentStatusHistory;
import com.veggofresh.delivery.entity.DeliveryKycStatus;
import com.veggofresh.delivery.entity.DeliveryOtp;
import com.veggofresh.delivery.entity.DeliveryOtpType;
import com.veggofresh.delivery.entity.DeliveryPartnerProfile;
import com.veggofresh.delivery.entity.DeliveryProofOfDelivery;
import com.veggofresh.delivery.entity.EarningRecord;
import com.veggofresh.delivery.repository.DeliveryAssignmentRepository;
import com.veggofresh.delivery.repository.DeliveryAssignmentStatusHistoryRepository;
import com.veggofresh.delivery.repository.DeliveryOtpRepository;
import com.veggofresh.delivery.repository.DeliveryPartnerProfileRepository;
import com.veggofresh.delivery.repository.DeliveryProofOfDeliveryRepository;
import com.veggofresh.delivery.repository.EarningRecordRepository;
import com.veggofresh.delivery.service.DeliveryAssignmentService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.customer.entity.Order;
import com.veggofresh.customer.repository.OrderRepository;
import com.veggofresh.payment.service.PaymentService;
import com.veggofresh.vendor.dto.ShopSummaryDto;
import com.veggofresh.vendor.service.ShopLookupService;
import com.veggofresh.platform.storage.CloudinaryService;
import com.veggofresh.platform.storage.CloudinaryUploadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REBUILT THIS ROUND -- real atomic accept, real radius-enforced
 * broadcast/accept,
 * real bounded re-broadcast loop reading Admin's configured settings,
 * pickup-OTP,
 * cancel-after-accept, and the assignDeliveryAgent/cancelOrderSystemInitiated
 * wiring.
 * Full detail on every change in NOTES_DELIVERY.md -- this class's javadoc only
 * flags
 * the highlights inline near each change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryAssignmentServiceImpl implements DeliveryAssignmentService {

    // OTP expiry is now Admin-configurable (PlatformSettings.otpExpiryMinutes, no
    // ceiling) -- see issuePickupOtp()/issueDropOtp(). Previously a hardcoded
    // OTP_EXPIRY_MINUTES = 15 constant here.
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final BigDecimal BASE_PAY = BigDecimal.valueOf(20);
    private static final BigDecimal RATE_PER_KM = BigDecimal.valueOf(8);
    // peakBonus and tip are ALWAYS zero -- no surge/demand system and no
    // tip-collection mechanism exist anywhere yet. See NOTES.md.
    private static final List<DeliveryAssignmentStatus> TERMINAL_STATUSES = List.of(
            DeliveryAssignmentStatus.DELIVERED, DeliveryAssignmentStatus.REJECTED,
            DeliveryAssignmentStatus.EXPIRED, DeliveryAssignmentStatus.CANCELLED);
    private static final List<DeliveryAssignmentStatus> CANCELLABLE_STATUSES = List.of(
            DeliveryAssignmentStatus.ACCEPTED, DeliveryAssignmentStatus.ARRIVED_AT_STORE);

    private final DeliveryAssignmentRepository assignmentRepository;
    private final DeliveryAssignmentStatusHistoryRepository historyRepository;
    private final DeliveryPartnerProfileRepository partnerRepository;
    private final DeliveryOtpRepository otpRepository;
    private final DeliveryProofOfDeliveryRepository proofRepository;
    private final EarningRecordRepository earningRecordRepository;
    private final CustomerOrderService customerOrderService;
    private final UserLookupService userLookupService;
    private final CloudinaryService cloudinaryService;
    private final PlatformSettingsService platformSettingsService;
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final ShopLookupService shopLookupService;

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryAssignmentResponseDto> getNearbyAssignments(UUID deliveryPartnerUserId, double lat, double lng,
            double radiusKm) {
        // NEW: clamped to Admin's configured radius -- a partner can narrow their own
        // view (e.g. "just show me within 2km") but can never see further than the real
        // eligibility boundary, closing the "discovery filter isn't an enforcement
        // boundary" gap flagged in the audit.
        double adminRadius = platformSettingsService.getDeliveryRadiusKm();
        double effectiveRadius = (radiusKm > 0 && radiusKm < adminRadius) ? radiusKm : adminRadius;

        return assignmentRepository.findAll().stream()
                .filter(a -> a.getStatus() == DeliveryAssignmentStatus.PENDING)
                .filter(a -> haversineKm(lat, lng, a.getPickupLatitude(), a.getPickupLongitude()) <= effectiveRadius)
                .map(this::mapToLightDto)
                .collect(Collectors.toList());
    }

    @Override
    public DeliveryAssignmentResponseDto acceptAssignment(UUID deliveryPartnerUserId, UUID orderId) {
        DeliveryPartnerProfile partner = requireApprovedPartner(deliveryPartnerUserId);

        DeliveryAssignment assignment = findByOrderAndStatus(orderId, DeliveryAssignmentStatus.PENDING,
                "DELIVERY_ASSIGNMENT_NOT_AVAILABLE", "No pending assignment for this order");

        if (Instant.now().isAfter(assignment.getExpiresAt())) {
            throw new BusinessException("DELIVERY_ASSIGNMENT_EXPIRED", "This assignment has expired", HttpStatus.GONE);
        }

        // NEW: real accept-time radius enforcement -- previously nothing checked the
        // calling partner's own distance from the pickup point at all.
        if (partner.getCurrentLatitude() == null || partner.getCurrentLongitude() == null) {
            throw new BusinessException("DELIVERY_LOCATION_UNKNOWN",
                    "Your current location isn't set -- go online again to refresh it before accepting",
                    HttpStatus.BAD_REQUEST);
        }
        double distanceToPickup = haversineKm(partner.getCurrentLatitude(), partner.getCurrentLongitude(),
                assignment.getPickupLatitude(), assignment.getPickupLongitude());
        double radiusKm = platformSettingsService.getDeliveryRadiusKm();
        if (distanceToPickup > radiusKm) {
            throw new BusinessException("DELIVERY_OUT_OF_RANGE",
                    "You are outside the delivery radius for this order", HttpStatus.BAD_REQUEST);
        }

        // NEW: real atomic accept -- a single conditional UPDATE, not a
        // read-then-write.
        // 0 rows affected means someone else already claimed it between our read above
        // and this write; that's reported cleanly, not as an unhandled 500.
        int claimed = assignmentRepository.atomicClaim(assignment.getId(), deliveryPartnerUserId,
                DeliveryAssignmentStatus.ACCEPTED, DeliveryAssignmentStatus.PENDING);
        if (claimed == 0) {
            throw new BusinessException("DELIVERY_ASSIGNMENT_ALREADY_TAKEN",
                    "Someone else already accepted this order", HttpStatus.CONFLICT);
        }

        DeliveryAssignment refreshed = assignmentRepository.findById(assignment.getId()).orElseThrow();
        recordHistory(refreshed.getId(), DeliveryAssignmentStatus.ACCEPTED);

        issuePickupOtp(refreshed);

        // FIXED THIS ROUND: previously called customerOrderService.acceptOrder(orderId)
        // here, which is semantically wrong -- that's the VENDOR's action on the ORDER
        // (PLACED -> CONFIRMED), not the delivery partner's action on the ASSIGNMENT.
        // By
        // the time delivery is even involved the order is already well past PLACED, so
        // that call would throw INVALID_ORDER_STATE_TRANSITION now that a real
        // ready-for-pickup trigger exists upstream. The correct call here is
        // assignDeliveryAgent -- exists on CustomerOrderService, was never called
        // before.
        String agentPhone = userLookupService.findById(deliveryPartnerUserId)
                .map(UserSummaryDto::getPhone).orElse(null);
        customerOrderService.assignDeliveryAgent(orderId, partner.getFullName(), agentPhone,
                null /* no profile photo field exists on DeliveryPartnerProfile yet */,
                null /* no real ETA calculation exists yet -- see NOTES_DELIVERY.md */);

        // CHANGED THIS ROUND: previously returned mapToLightDto() here, even
        // though the partner now legitimately owns this assignment and should
        // immediately see shop phone, customer name/phone, items, and invoice
        // total without a second call.
        return mapToFullDto(refreshed);
    }

    @Override
    public DeliveryAssignmentResponseDto rejectAssignment(UUID deliveryPartnerUserId, UUID orderId) {
        DeliveryAssignment assignment = findByOrderAndStatus(orderId, DeliveryAssignmentStatus.PENDING,
                "DELIVERY_ASSIGNMENT_NOT_AVAILABLE", "No pending assignment for this order");

        assignment.setStatus(DeliveryAssignmentStatus.REJECTED);
        assignmentRepository.save(assignment);
        recordHistory(assignment.getId(), DeliveryAssignmentStatus.REJECTED);

        reassign(assignment, deliveryPartnerUserId);

        return mapToLightDto(assignment);
    }

    @Override
    public DeliveryAssignmentResponseDto cancelAssignment(UUID deliveryPartnerUserId, UUID orderId, String reason) {
        DeliveryAssignment assignment = assignmentRepository
                .findByOrderIdAndStatusIn(orderId, CANCELLABLE_STATUSES)
                .orElseThrow(() -> new BusinessException("DELIVERY_ASSIGNMENT_NOT_CANCELLABLE",
                        "No cancellable assignment (must be ACCEPTED or ARRIVED_AT_STORE) for this order",
                        HttpStatus.BAD_REQUEST));

        if (!deliveryPartnerUserId.equals(assignment.getDeliveryPartnerUserId())) {
            throw new BusinessException("DELIVERY_ASSIGNMENT_NOT_OWNED", "This assignment does not belong to you",
                    HttpStatus.FORBIDDEN);
        }

        assignment.setStatus(DeliveryAssignmentStatus.CANCELLED);
        assignmentRepository.save(assignment);
        recordHistory(assignment.getId(), DeliveryAssignmentStatus.CANCELLED);

        log.info("Delivery partner {} cancelled assignment for order {}: {}", deliveryPartnerUserId, orderId, reason);
        reassign(assignment, deliveryPartnerUserId);

        return mapToLightDto(assignment);
    }

    @Override
    public DeliveryAssignmentResponseDto markArrivedAtStore(UUID deliveryPartnerUserId, UUID orderId) {
        DeliveryAssignment assignment = requireOwnedAssignment(deliveryPartnerUserId, orderId,
                DeliveryAssignmentStatus.ACCEPTED);

        assignment.setStatus(DeliveryAssignmentStatus.ARRIVED_AT_STORE);
        assignmentRepository.save(assignment);
        recordHistory(assignment.getId(), DeliveryAssignmentStatus.ARRIVED_AT_STORE);

        return mapToFullDto(assignment);
    }

    @Override
    public DeliveryAssignmentResponseDto markPickedUp(UUID deliveryPartnerUserId, UUID orderId) {
        DeliveryAssignment assignment = requireOwnedAssignment(deliveryPartnerUserId, orderId,
                DeliveryAssignmentStatus.ARRIVED_AT_STORE);

        // NEW REQUIREMENT: pickup OTP must be verified first -- mirrors the existing
        // drop-OTP-required-before-completeDelivery() pattern exactly.
        DeliveryOtp pickupOtp = otpRepository.findByAssignmentIdAndType(assignment.getId(), DeliveryOtpType.PICKUP)
                .orElseThrow(() -> new BusinessException("DELIVERY_PICKUP_OTP_NOT_FOUND",
                        "No pickup OTP issued for this assignment", HttpStatus.NOT_FOUND));
        if (!pickupOtp.isVerified()) {
            throw new BusinessException("DELIVERY_PICKUP_OTP_NOT_VERIFIED",
                    "Pickup OTP must be verified before marking picked up", HttpStatus.BAD_REQUEST);
        }

        assignment.setStatus(DeliveryAssignmentStatus.PICKED_UP);
        assignmentRepository.save(assignment);
        recordHistory(assignment.getId(), DeliveryAssignmentStatus.PICKED_UP);

        issueDropOtp(assignment, orderId);
        customerOrderService.updateOrderStatus(orderId, "OUT_FOR_DELIVERY");

        return mapToFullDto(assignment);
    }

    @Override
    public DeliveryAssignmentResponseDto markArrivedAtDrop(UUID deliveryPartnerUserId, UUID orderId) {
        DeliveryAssignment assignment = requireOwnedAssignment(deliveryPartnerUserId, orderId,
                DeliveryAssignmentStatus.PICKED_UP);

        assignment.setStatus(DeliveryAssignmentStatus.ARRIVED_AT_DROP);
        assignmentRepository.save(assignment);
        recordHistory(assignment.getId(), DeliveryAssignmentStatus.ARRIVED_AT_DROP);

        return mapToFullDto(assignment);
    }

    @Override
    public void verifyPickupOtp(UUID deliveryPartnerUserId, UUID orderId, String otp) {
        DeliveryAssignment assignment = requireOwnedAssignment(deliveryPartnerUserId, orderId,
                DeliveryAssignmentStatus.ARRIVED_AT_STORE);

        DeliveryOtp pickupOtp = otpRepository.findByAssignmentIdAndType(assignment.getId(), DeliveryOtpType.PICKUP)
                .orElseThrow(() -> new BusinessException("DELIVERY_PICKUP_OTP_NOT_FOUND",
                        "No pickup OTP issued for this assignment", HttpStatus.NOT_FOUND));

        verifyOtpInternal(pickupOtp, otp, "DELIVERY_PICKUP_OTP");
    }

    @Override
    public void verifyDeliveryOtp(UUID deliveryPartnerUserId, UUID orderId, String otp) {
        DeliveryAssignment assignment = assignmentRepository
                .findByOrderIdAndStatusIn(orderId,
                        List.of(DeliveryAssignmentStatus.PICKED_UP, DeliveryAssignmentStatus.ARRIVED_AT_DROP))
                .orElseThrow(() -> new BusinessException("DELIVERY_ASSIGNMENT_NOT_FOUND",
                        "No assignment awaiting OTP verification for this order", HttpStatus.NOT_FOUND));

        if (!deliveryPartnerUserId.equals(assignment.getDeliveryPartnerUserId())) {
            throw new BusinessException("DELIVERY_ASSIGNMENT_NOT_OWNED", "This assignment does not belong to you",
                    HttpStatus.FORBIDDEN);
        }

        DeliveryOtp dropOtp = otpRepository.findByAssignmentIdAndType(assignment.getId(), DeliveryOtpType.DROP)
                .orElseThrow(() -> new BusinessException("DELIVERY_OTP_NOT_FOUND", "No OTP issued for this delivery",
                        HttpStatus.NOT_FOUND));

        verifyOtpInternal(dropOtp, otp, "DELIVERY_OTP");
    }

    @Override
    public ProofOfDeliveryResponseDto submitProofOfDelivery(UUID deliveryPartnerUserId, UUID orderId,
            MultipartFile photo,
            boolean deliveredToCustomerDirectly, boolean leftAtFrontDoor,
            boolean packagingIntact, boolean addressVerifiedManually, String notes) {
        DeliveryAssignment assignment = assignmentRepository
                .findByOrderIdAndStatusIn(orderId,
                        List.of(DeliveryAssignmentStatus.PICKED_UP, DeliveryAssignmentStatus.ARRIVED_AT_DROP))
                .orElseThrow(() -> new BusinessException("DELIVERY_ASSIGNMENT_NOT_FOUND",
                        "No assignment ready for proof of delivery on this order", HttpStatus.NOT_FOUND));

        if (!deliveryPartnerUserId.equals(assignment.getDeliveryPartnerUserId())) {
            throw new BusinessException("DELIVERY_ASSIGNMENT_NOT_OWNED", "This assignment does not belong to you",
                    HttpStatus.FORBIDDEN);
        }

        if (photo == null || photo.isEmpty()) {
            throw new BusinessException("DELIVERY_PROOF_PHOTO_REQUIRED", "A delivery photo is required",
                    HttpStatus.BAD_REQUEST);
        }

        DeliveryProofOfDelivery proof = proofRepository.findByAssignmentId(assignment.getId())
                .orElseGet(() -> {
                    DeliveryProofOfDelivery newProof = new DeliveryProofOfDelivery();
                    newProof.setAssignmentId(assignment.getId());
                    return newProof;
                });

        // Upload the new photo first -- only swap over and delete the old one (in the
        // rare case of a resubmission for the same assignment) once the new upload has
        // actually succeeded.
        CloudinaryUploadResult upload = cloudinaryService.uploadImage(
                photo, "veggofresh/delivery-proof/" + assignment.getId());
        String oldPublicId = proof.getPublicId();

        proof.setPhotoUrl(upload.url());
        proof.setPublicId(upload.publicId());
        proof.setDeliveredToCustomerDirectly(deliveredToCustomerDirectly);
        proof.setLeftAtFrontDoor(leftAtFrontDoor);
        proof.setPackagingIntact(packagingIntact);
        proof.setAddressVerifiedManually(addressVerifiedManually);
        proof.setNotes(notes);
        proofRepository.save(proof);

        cloudinaryService.deleteQuietly(oldPublicId);

        return mapProofToDto(proof);
    }

    @Override
    public DeliveryAssignmentResponseDto completeDelivery(UUID deliveryPartnerUserId, UUID orderId) {
        DeliveryAssignment assignment = requireOwnedAssignment(deliveryPartnerUserId, orderId,
                DeliveryAssignmentStatus.ARRIVED_AT_DROP);

        DeliveryOtp dropOtp = otpRepository.findByAssignmentIdAndType(assignment.getId(), DeliveryOtpType.DROP)
                .orElseThrow(() -> new BusinessException("DELIVERY_OTP_NOT_FOUND", "No OTP issued for this delivery",
                        HttpStatus.NOT_FOUND));

        if (!dropOtp.isVerified()) {
            throw new BusinessException("DELIVERY_OTP_NOT_VERIFIED",
                    "Delivery OTP must be verified before completing delivery", HttpStatus.BAD_REQUEST);
        }

        DeliveryProofOfDelivery proof = proofRepository.findByAssignmentId(assignment.getId())
                .filter(p -> p.getPhotoUrl() != null)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROOF_REQUIRED",
                        "Proof of delivery (photo) must be submitted before completing delivery",
                        HttpStatus.BAD_REQUEST));

        assignment.setStatus(DeliveryAssignmentStatus.DELIVERED);
        assignmentRepository.save(assignment);
        recordHistory(assignment.getId(), DeliveryAssignmentStatus.DELIVERED);

        customerOrderService.updateOrderStatus(orderId, "DELIVERED");

        recordEarning(assignment);

        try {
            orderRepository.findById(orderId).ifPresent(order -> {
                // Vendor is paid the admin-set vendor price (snapshotted on each item); the platform keeps
                // platform fee + margin - coupon discount. Everything comes from what was saved on the order.
                java.util.List<DeliverySettlementMath.Line> lines = order.getItems() == null
                        ? java.util.List.of()
                        : order.getItems().stream()
                                .map(i -> new DeliverySettlementMath.Line(i.getPrice(), i.getVendorUnitPrice(), i.getQuantity()))
                                .collect(java.util.stream.Collectors.toList());
                BigDecimal platformFee = order.getEstimatedTax() != null ? order.getEstimatedTax()
                        : platformSettingsService.getPlatformFeeAmount();
                DeliverySettlementMath.Result money =
                        DeliverySettlementMath.compute(lines, platformFee, order.getPromoDiscount());
                BigDecimal deliveryFee = order.getDeliveryFee() != null ? order.getDeliveryFee()
                        : platformSettingsService.getDeliveryFeeAmount(); // fallback to admin-configured fee
                UUID vendorUserId = order.getAcceptedShopId() != null
                        ? shopLookupService.findOwnerUserIdByShopId(order.getAcceptedShopId()).orElse(null)
                        : null;
                paymentService.onDeliveryCompleted(orderId, money.vendorAmount(), deliveryFee,
                        money.platformAmount(), vendorUserId, deliveryPartnerUserId);
            });
        } catch (Exception e) {
            log.error("Failed to execute payment settlement for delivered order {}: {}", orderId, e.getMessage(), e);
        }

        return mapToFullDto(assignment);
    }

    @Override
    public void expireStaleAssignments() {
        List<DeliveryAssignment> stale = assignmentRepository
                .findByStatusAndExpiresAtBefore(DeliveryAssignmentStatus.PENDING, Instant.now());

        for (DeliveryAssignment assignment : stale) {
            assignment.setStatus(DeliveryAssignmentStatus.EXPIRED);
            assignmentRepository.save(assignment);
            recordHistory(assignment.getId(), DeliveryAssignmentStatus.EXPIRED);
            reassign(assignment, assignment.getDeliveryPartnerUserId());
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void scheduledExpirySweep() {
        try {
            expireStaleAssignments();
        } catch (Exception e) {
            log.error("Error while expiring stale delivery assignments: {}", e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryAssignmentResponseDto getAssignmentDetail(UUID deliveryPartnerUserId, UUID orderId) {
        DeliveryAssignment assignment = assignmentRepository.findByOrderId(orderId).stream()
                .filter(a -> deliveryPartnerUserId.equals(a.getDeliveryPartnerUserId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("DELIVERY_ASSIGNMENT_NOT_FOUND",
                        "No assignment found for this order belonging to you", HttpStatus.NOT_FOUND));

        return mapToFullDto(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryAssignmentResponseDto> getMyOrders(UUID deliveryPartnerUserId, String status,
            Pageable pageable) {
        boolean wantCompleted = "completed".equalsIgnoreCase(status);

        List<DeliveryAssignment> all = assignmentRepository.findAll().stream()
                .filter(a -> deliveryPartnerUserId.equals(a.getDeliveryPartnerUserId()))
                .filter(a -> wantCompleted ? a.getStatus() == DeliveryAssignmentStatus.DELIVERED
                        : !TERMINAL_STATUSES.contains(a.getStatus()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .collect(Collectors.toList());

        int start = Math.min((int) pageable.getOffset(), all.size());
        int end = Math.min(start + pageable.getPageSize(), all.size());
        // CHANGED THIS ROUND: every assignment in this list is already owned by
        // this partner (deliveryPartnerUserId is null until accept, and the
        // filter above requires it to equal this partner's id) -- so light dto
        // was under-serving an already-entitled view. Full detail now.
        List<DeliveryAssignmentResponseDto> pageContent = all.subList(start, end).stream()
                .map(this::mapToFullDto)
                .collect(Collectors.toList());

        return new PageImpl<>(pageContent, pageable, all.size());
    }

    @Override
    public void createAssignmentForOrder(UUID orderId, UUID customerUserId, UUID shopOwnerUserId, String shopName,
            String shopAddress,
            double pickupLat, double pickupLng, double dropLat, double dropLng, String dropAddress) {
        DeliveryAssignment assignment = new DeliveryAssignment();
        assignment.setOrderId(orderId);
        assignment.setCustomerUserId(customerUserId);
        assignment.setShopOwnerUserId(shopOwnerUserId);
        assignment.setShopName(shopName);
        assignment.setShopAddress(shopAddress);
        assignment.setPickupLatitude(pickupLat);
        assignment.setPickupLongitude(pickupLng);
        assignment.setDropLatitude(dropLat);
        assignment.setDropLongitude(dropLng);
        assignment.setDropAddress(dropAddress);
        assignment.setStatus(DeliveryAssignmentStatus.PENDING);
        assignment.setAssignedAt(Instant.now());
        // NEW: reads Admin's real configured timeout instead of a hardcoded constant.
        assignment.setExpiresAt(
                Instant.now().plus(platformSettingsService.getDeliveryAcceptTimeoutSeconds(), ChronoUnit.SECONDS));
        assignmentRepository.save(assignment);
        recordHistory(assignment.getId(), DeliveryAssignmentStatus.PENDING);

        if (findNearestAvailablePartner(pickupLat, pickupLng, List.of()) == null) {
            log.warn("No online, KYC-approved delivery partner currently available for order {}", orderId);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * NEW THIS ROUND: bounded. Previously this would loop reassigning forever as
     * long
     * as any partner existed anywhere -- no cap on rounds or total elapsed time.
     * Now
     * checks Admin's two independent limits (whichever hits first) BEFORE creating
     * another round; hitting either one cancels the order for real via
     * customerOrderService.cancelOrderSystemInitiated(...), which also triggers the
     * wallet refund (see Customer/Payment rounds).
     */
    private void reassign(DeliveryAssignment previous, UUID excludePartnerUserId) {
        long roundsSoFar = assignmentRepository.countByOrderId(previous.getOrderId());
        Instant firstBroadcastAt = assignmentRepository.findFirstByOrderIdOrderByCreatedAtAsc(previous.getOrderId())
                .map(DeliveryAssignment::getCreatedAt)
                .orElse(previous.getCreatedAt());

        int maxRounds = platformSettingsService.getRebroadcastMaxRounds();
        int maxElapsedMinutes = platformSettingsService.getRebroadcastMaxElapsedMinutes();

        boolean roundsExceeded = roundsSoFar >= maxRounds;
        boolean elapsedExceeded = Instant.now().isAfter(firstBroadcastAt.plus(maxElapsedMinutes, ChronoUnit.MINUTES));

        if (roundsExceeded || elapsedExceeded) {
            log.warn(
                    "Re-broadcast limit hit for order {} (rounds so far={}, max={}, elapsed cap hit={}) -- cancelling order",
                    previous.getOrderId(), roundsSoFar, maxRounds, elapsedExceeded);
            customerOrderService.cancelOrderSystemInitiated(previous.getOrderId(),
                    "No delivery partner accepted this order within the allowed re-broadcast limit");
            return;
        }

        List<UUID> alreadyTried = assignmentRepository.findByOrderId(previous.getOrderId()).stream()
                .map(DeliveryAssignment::getDeliveryPartnerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (excludePartnerUserId != null)
            alreadyTried.add(excludePartnerUserId);

        DeliveryPartnerProfile nextPartner = findNearestAvailablePartner(
                previous.getPickupLatitude(), previous.getPickupLongitude(), alreadyTried);

        if (nextPartner == null) {
            log.warn("No further delivery partners available to reassign order {}", previous.getOrderId());
            return;
        }

        DeliveryAssignment newAssignment = new DeliveryAssignment();
        newAssignment.setOrderId(previous.getOrderId());
        newAssignment.setCustomerUserId(previous.getCustomerUserId());
        newAssignment.setShopOwnerUserId(previous.getShopOwnerUserId());
        newAssignment.setShopName(previous.getShopName());
        newAssignment.setShopAddress(previous.getShopAddress());
        newAssignment.setPickupLatitude(previous.getPickupLatitude());
        newAssignment.setPickupLongitude(previous.getPickupLongitude());
        newAssignment.setDropLatitude(previous.getDropLatitude());
        newAssignment.setDropLongitude(previous.getDropLongitude());
        newAssignment.setDropAddress(previous.getDropAddress());
        newAssignment.setStatus(DeliveryAssignmentStatus.PENDING);
        newAssignment.setAssignedAt(Instant.now());
        newAssignment.setExpiresAt(
                Instant.now().plus(platformSettingsService.getDeliveryAcceptTimeoutSeconds(), ChronoUnit.SECONDS));
        assignmentRepository.save(newAssignment);
        recordHistory(newAssignment.getId(), DeliveryAssignmentStatus.PENDING);
    }

    /**
     * Still only used to log a "nobody available" warning at creation time, same as
     * before -- the real broadcast surface is getNearbyAssignments (now correctly
     * radius-clamped) plus the real accept-time radius check in acceptAssignment().
     * This
     * method does NOT pre-assign or notify a specific partner; it never did.
     */
    private DeliveryPartnerProfile findNearestAvailablePartner(double lat, double lng, List<UUID> excludeUserIds) {
        return partnerRepository.findByOnlineTrueAndKycStatus(DeliveryKycStatus.APPROVED).stream()
                .filter(p -> p.getCurrentLatitude() != null && p.getCurrentLongitude() != null)
                .filter(p -> !excludeUserIds.contains(p.getUserId()))
                .min((a, b) -> Double.compare(
                        haversineKm(lat, lng, a.getCurrentLatitude(), a.getCurrentLongitude()),
                        haversineKm(lat, lng, b.getCurrentLatitude(), b.getCurrentLongitude())))
                .orElse(null);
    }

    private DeliveryPartnerProfile requireApprovedPartner(UUID userId) {
        DeliveryPartnerProfile partner = partnerRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("DELIVERY_PROFILE_NOT_FOUND",
                        "Delivery partner profile not found", HttpStatus.NOT_FOUND));

        if (partner.getKycStatus() != DeliveryKycStatus.APPROVED) {
            throw new BusinessException("DELIVERY_KYC_NOT_APPROVED", "KYC not approved", HttpStatus.FORBIDDEN);
        }
        return partner;
    }

    private DeliveryAssignment findByOrderAndStatus(UUID orderId, DeliveryAssignmentStatus status, String errorCode,
            String message) {
        return assignmentRepository.findByOrderIdAndStatusIn(orderId, List.of(status))
                .orElseThrow(() -> new BusinessException(errorCode, message, HttpStatus.NOT_FOUND));
    }

    private DeliveryAssignment requireOwnedAssignment(UUID deliveryPartnerUserId, UUID orderId,
            DeliveryAssignmentStatus expectedStatus) {
        DeliveryAssignment assignment = assignmentRepository
                .findByOrderIdAndStatusIn(orderId, List.of(expectedStatus))
                .orElseThrow(() -> new BusinessException("DELIVERY_ASSIGNMENT_NOT_FOUND",
                        "No assignment in expected state for this order", HttpStatus.NOT_FOUND));

        if (!deliveryPartnerUserId.equals(assignment.getDeliveryPartnerUserId())) {
            throw new BusinessException("DELIVERY_ASSIGNMENT_NOT_OWNED", "This assignment does not belong to you",
                    HttpStatus.FORBIDDEN);
        }
        return assignment;
    }

    /**
     * Issued right after a successful accept, mirroring how the drop OTP is issued
     * right after pickup.
     * Also called directly by regeneratePickupOtp() -- same logic, safe to call
     * again on the same
     * assignment since it always overwrites otpCode/expiresAt/verified/attempts
     * regardless of whether
     * a row already existed.
     */
    private void issuePickupOtp(DeliveryAssignment assignment) {
        String otpCode = generateSixDigitOtp();
        DeliveryOtp otp = otpRepository.findByAssignmentIdAndType(assignment.getId(), DeliveryOtpType.PICKUP)
                .orElseGet(DeliveryOtp::new);
        otp.setAssignmentId(assignment.getId());
        otp.setType(DeliveryOtpType.PICKUP);
        otp.setOtpCode(otpCode);
        otp.setExpiresAt(Instant.now().plus(platformSettingsService.getOtpExpiryMinutes(), ChronoUnit.MINUTES));
        otp.setVerified(false);
        otp.setAttempts(0);
        otpRepository.save(otp);

        // MOCK — real delivery, this needs to actually reach the Vendor's screen (their
        // "ready for pickup"/order-detail view). No such Vendor-facing endpoint exists
        // yet to READ this value -- that's Vendor-round work. See NOTES_DELIVERY.md for
        // the DeliveryPickupInfoService contract built this round specifically for
        // that.
        log.info("MOCK — Delivery pickup OTP {} issued for assignment {}", otpCode, assignment.getId());
    }

    /**
     * Renamed from issueDeliveryOtp for clarity now that there's also a pickup OTP
     * -- behavior unchanged except 4->6 digits.
     * Also called directly by regenerateDropOtp() -- same logic, safe to call again
     * on the same assignment.
     * NEW: pushes the code to the Customer's order the instant it's generated
     * (initial issuance at
     * pickup, or a later regeneration) via
     * CustomerOrderService.setDropOtpAvailable() -- the customer's
     * track screen and dedicated drop-OTP endpoint both just read that same field,
     * so this one push
     * keeps both in sync automatically, no matter which path (issue vs. regenerate)
     * produced the code.
     */
    private void issueDropOtp(DeliveryAssignment assignment, UUID orderId) {
        String otpCode = generateSixDigitOtp();
        DeliveryOtp otp = otpRepository.findByAssignmentIdAndType(assignment.getId(), DeliveryOtpType.DROP)
                .orElseGet(DeliveryOtp::new);
        otp.setAssignmentId(assignment.getId());
        otp.setType(DeliveryOtpType.DROP);
        otp.setOtpCode(otpCode);
        otp.setExpiresAt(Instant.now().plus(platformSettingsService.getOtpExpiryMinutes(), ChronoUnit.MINUTES));
        otp.setVerified(false);
        otp.setAttempts(0);
        otpRepository.save(otp);

        customerOrderService.setDropOtpAvailable(orderId, otpCode);

        log.info("MOCK — Delivery drop OTP {} issued for assignment {}", otpCode, assignment.getId());
    }

    @Override
    public void regeneratePickupOtp(UUID deliveryPartnerUserId, UUID orderId) {
        DeliveryAssignment assignment = assignmentRepository
                .findByOrderIdAndStatusIn(orderId,
                        List.of(DeliveryAssignmentStatus.ACCEPTED, DeliveryAssignmentStatus.ARRIVED_AT_STORE))
                .orElseThrow(() -> new BusinessException("DELIVERY_ASSIGNMENT_NOT_FOUND",
                        "No assignment awaiting a pickup OTP for this order", HttpStatus.NOT_FOUND));

        if (!deliveryPartnerUserId.equals(assignment.getDeliveryPartnerUserId())) {
            throw new BusinessException("DELIVERY_ASSIGNMENT_NOT_OWNED", "This assignment does not belong to you",
                    HttpStatus.FORBIDDEN);
        }

        issuePickupOtp(assignment);
    }

    @Override
    public void regenerateDropOtp(UUID deliveryPartnerUserId, UUID orderId) {
        DeliveryAssignment assignment = assignmentRepository
                .findByOrderIdAndStatusIn(orderId,
                        List.of(DeliveryAssignmentStatus.PICKED_UP, DeliveryAssignmentStatus.ARRIVED_AT_DROP))
                .orElseThrow(() -> new BusinessException("DELIVERY_ASSIGNMENT_NOT_FOUND",
                        "No assignment awaiting a drop OTP for this order", HttpStatus.NOT_FOUND));

        if (!deliveryPartnerUserId.equals(assignment.getDeliveryPartnerUserId())) {
            throw new BusinessException("DELIVERY_ASSIGNMENT_NOT_OWNED", "This assignment does not belong to you",
                    HttpStatus.FORBIDDEN);
        }

        issueDropOtp(assignment, orderId);
    }

    private String generateSixDigitOtp() {
        return String.format("%06d", new Random().nextInt(999999));
    }

    private void verifyOtpInternal(DeliveryOtp otp, String suppliedCode, String errorPrefix) {
        if (otp.isVerified()) {
            throw new BusinessException(errorPrefix + "_ALREADY_VERIFIED", "OTP already verified");
        }
        if (Instant.now().isAfter(otp.getExpiresAt())) {
            throw new BusinessException(errorPrefix + "_EXPIRED", "OTP has expired");
        }

        otp.setAttempts(otp.getAttempts() + 1);
        if (otp.getAttempts() > MAX_OTP_ATTEMPTS) {
            otpRepository.save(otp);
            throw new BusinessException(errorPrefix + "_MAX_ATTEMPTS", "Maximum OTP attempts exceeded");
        }

        if (!otp.getOtpCode().equals(suppliedCode)) {
            otpRepository.save(otp);
            throw new BusinessException(errorPrefix + "_INVALID", "Invalid OTP", HttpStatus.UNAUTHORIZED);
        }

        otp.setVerified(true);
        otpRepository.save(otp);
    }

    private void recordEarning(DeliveryAssignment assignment) {
        BigDecimal total = estimateEarning(assignment);

        double distanceKm = haversineKm(assignment.getPickupLatitude(), assignment.getPickupLongitude(),
                assignment.getDropLatitude(), assignment.getDropLongitude());
        BigDecimal distanceFare = RATE_PER_KM.multiply(BigDecimal.valueOf(distanceKm))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal peakBonus = BigDecimal.ZERO; // no surge/demand system exists yet
        BigDecimal tip = BigDecimal.ZERO; // no tip-collection mechanism exists yet

        EarningRecord record = new EarningRecord();
        record.setDeliveryPartnerUserId(assignment.getDeliveryPartnerUserId());
        record.setOrderId(assignment.getOrderId());
        record.setBasePay(BASE_PAY);
        record.setDistanceFare(distanceFare);
        record.setPeakBonus(peakBonus);
        record.setTip(tip);
        record.setAmount(total);
        earningRecordRepository.save(record);
    }

    private void recordHistory(UUID assignmentId, DeliveryAssignmentStatus status) {
        DeliveryAssignmentStatusHistory entry = new DeliveryAssignmentStatusHistory();
        entry.setAssignmentId(assignmentId);
        entry.setStatus(status);
        historyRepository.save(entry);
    }

    private DeliveryAssignmentResponseDto mapToLightDto(DeliveryAssignment a) {
        // Thumbnails only -- not customer identity, not item breakdown -- so
        // safe to resolve pre-accept. Reuses the exact same real data path
        // (CatalogProduct.imageUrl via the shared OrderResponseMapper) that
        // mapToFullDto and Customer/Vendor's own order views already use.
        // Defensive try/catch, same reasoning as mapToFullDto below: this is
        // a nice-to-have preview, not something that should ever break the
        // nearby-requests list if order lookup fails for any reason.
        List<String> itemThumbnails = null;
        try {
            itemThumbnails = customerOrderService.getOrderByIdForFulfillment(a.getOrderId()).getItemThumbnails();
        } catch (BusinessException e) {
            log.warn("Could not resolve item thumbnails for assignment {} (orderId={}): {}",
                    a.getId(), a.getOrderId(), e.getMessage());
        }

        return DeliveryAssignmentResponseDto.builder()
                .id(a.getId())
                .orderId(a.getOrderId())
                .status(a.getStatus())
                .pickupLatitude(a.getPickupLatitude())
                .pickupLongitude(a.getPickupLongitude())
                .dropLatitude(a.getDropLatitude())
                .dropLongitude(a.getDropLongitude())
                .dropAddress(a.getDropAddress())
                .assignedAt(a.getAssignedAt())
                .expiresAt(a.getExpiresAt())
                .shopName(a.getShopName())
                .shopAddress(a.getShopAddress())
                .estimatedEarning(estimateEarning(a))
                .itemThumbnails(itemThumbnails)
                .build();
    }

    /**
     * Real settlement formula (recordEarning() below) computed early as a
     * preview, using the same pickup/drop coordinates -- both already known
     * the moment the assignment is created (dispatch time), well before any
     * partner has accepted. peakBonus and tip are always zero right now, so
     * this preview matches the eventual recorded EarningRecord.amount exactly.
     */
    private BigDecimal estimateEarning(DeliveryAssignment a) {
        double distanceKm = haversineKm(a.getPickupLatitude(), a.getPickupLongitude(),
                a.getDropLatitude(), a.getDropLongitude());
        BigDecimal distanceFare = RATE_PER_KM.multiply(BigDecimal.valueOf(distanceKm))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return BASE_PAY.add(distanceFare);
    }

    private ProofOfDeliveryResponseDto mapProofToDto(DeliveryProofOfDelivery proof) {
        return ProofOfDeliveryResponseDto.builder()
                .photoUrl(proof.getPhotoUrl())
                .deliveredToCustomerDirectly(proof.isDeliveredToCustomerDirectly())
                .leftAtFrontDoor(proof.isLeftAtFrontDoor())
                .packagingIntact(proof.isPackagingIntact())
                .addressVerifiedManually(proof.isAddressVerifiedManually())
                .notes(proof.getNotes())
                .submittedAt(proof.getUpdatedAt())
                .build();
    }

    private DeliveryAssignmentResponseDto mapToFullDto(DeliveryAssignment a) {
        // Shop's real business phone -- not the owner's personal number.
        // Falls back to the owner's personal phone only if, for some reason,
        // no live Shop record can be resolved (defensive; shouldn't happen for
        // a real dispatched assignment).
        String shopPhone = a.getShopOwnerUserId() != null
                ? shopLookupService.findShopSummaryByOwnerUserId(a.getShopOwnerUserId())
                        .map(ShopSummaryDto::getBusinessPhone)
                        .orElseGet(() -> userLookupService.findById(a.getShopOwnerUserId())
                                .map(UserSummaryDto::getPhone).orElse(null))
                : null;
        String customerPhone = a.getCustomerUserId() != null
                ? userLookupService.findById(a.getCustomerUserId()).map(UserSummaryDto::getPhone).orElse(null)
                : null;

        List<DeliveryAssignmentResponseDto.TimelineEntryDto> timeline = historyRepository
                .findByAssignmentIdOrderByCreatedAtAsc(a.getId()).stream()
                .map(h -> DeliveryAssignmentResponseDto.TimelineEntryDto.builder()
                        .status(h.getStatus())
                        .occurredAt(h.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        ProofOfDeliveryResponseDto proofDto = proofRepository.findByAssignmentId(a.getId())
                .map(this::mapProofToDto)
                .orElse(null);

        // Full order contents + customer name -- reuses the same shared
        // OrderResponseMapper Customer/Vendor already use, so item names,
        // customer display name, and the order total all stay consistent
        // across every surface. This method is only ever reached via
        // getAssignmentDetail() (ownership-checked) or the post-accept action
        // methods below, so it's safe to resolve full order contents here.
        String customerName = null;
        List<DeliveryAssignmentResponseDto.OrderItemSummaryDto> items = null;
        BigDecimal orderTotal = null;
        List<String> itemThumbnails = null;
        try {
            var order = customerOrderService.getOrderByIdForFulfillment(a.getOrderId());
            customerName = order.getCustomerName();
            orderTotal = order.getTotalAmount();
            itemThumbnails = order.getItemThumbnails();
            items = order.getItems().stream()
                    .map(item -> DeliveryAssignmentResponseDto.OrderItemSummaryDto.builder()
                            .productId(item.getProductId())
                            .productName(item.getProductName())
                            .quantity(item.getQuantity())
                            .price(item.getPrice())
                            .subTotal(item.getSubTotal())
                            .build())
                    .collect(Collectors.toList());
        } catch (BusinessException e) {
            log.warn("Could not resolve order fulfillment detail for assignment {} (orderId={}): {}",
                    a.getId(), a.getOrderId(), e.getMessage());
        }

        return DeliveryAssignmentResponseDto.builder()
                .id(a.getId())
                .orderId(a.getOrderId())
                .status(a.getStatus())
                .pickupLatitude(a.getPickupLatitude())
                .pickupLongitude(a.getPickupLongitude())
                .dropLatitude(a.getDropLatitude())
                .dropLongitude(a.getDropLongitude())
                .dropAddress(a.getDropAddress())
                .assignedAt(a.getAssignedAt())
                .expiresAt(a.getExpiresAt())
                .shopName(a.getShopName())
                .shopAddress(a.getShopAddress())
                .shopPhone(shopPhone)
                .customerName(customerName)
                .customerPhone(customerPhone)
                .estimatedEarning(estimateEarning(a))
                .items(items)
                .orderTotal(orderTotal)
                .itemThumbnails(itemThumbnails)
                .timeline(timeline)
                .proofOfDelivery(proofDto)
                .build();
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
