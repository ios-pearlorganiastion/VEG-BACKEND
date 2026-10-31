package com.veggofresh.payment.service;

import com.veggofresh.admin.service.PlatformSettingsService;
import com.veggofresh.customer.entity.Order;
import com.veggofresh.customer.repository.OrderRepository;
import com.veggofresh.vendor.service.ShopLookupService;
import com.veggofresh.coupon.service.CouponCheckoutService;
import com.veggofresh.notification.entity.NotificationRecipientRole;
import com.veggofresh.notification.entity.NotificationType;
import com.veggofresh.notification.service.NotificationService;
import com.veggofresh.payment.client.RazorpayClient;
import com.veggofresh.payment.client.RazorpayPaymentStatus;
import com.veggofresh.payment.dto.PaymentHoldResponseDto;
import com.veggofresh.payment.dto.PaymentOrderLineAllocationDto;
import com.veggofresh.payment.entity.PaymentOrder;
import com.veggofresh.payment.entity.PaymentOrderLine;
import com.veggofresh.payment.entity.PaymentOrderLineStatus;
import com.veggofresh.payment.entity.PaymentOrderStatus;
import com.veggofresh.payment.repository.PaymentOrderLineRepository;
import com.veggofresh.payment.repository.PaymentOrderRepository;
import com.veggofresh.platform.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates Razorpay payment lifecycle: hold → verify → capture/void →
 * settle.
 *
 * Key design decisions:
 * 1. One Razorpay order per checkout() call (batch), not per Customer Order.
 * 2. Capture happens exactly once per batch, for the sum of ACCEPTED lines,
 * once
 * ALL lines resolve — because Razorpay only allows one capture per payment.
 * 3. Void/cancellation before capture = no money ever collected; after capture
 * = wallet credit.
 * 4. Settlement (Phase 3) = virtual ledger split, no new Razorpay calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentOrderLineRepository paymentOrderLineRepository;
    private final RazorpayClient razorpayClient;
    private final WalletService walletService;
    private final com.veggofresh.payment.config.RazorpayProperties razorpayProperties;
    private final NotificationService notificationService;
    private final PlatformSettingsService platformSettingsService;
    private final CouponCheckoutService couponService;
    private final OrderRepository orderRepository;
    private final ShopLookupService shopLookupService;

    @Override
    @Transactional
    public PaymentHoldResponseDto createHold(UUID userId, List<UUID> orderIds, List<BigDecimal> orderAmounts) {
        if (orderIds.isEmpty()) {
            throw new BusinessException("PAYMENT_NO_ORDERS", "Cannot create a payment hold with no orders",
                    HttpStatus.BAD_REQUEST);
        }

        BigDecimal total = orderAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1. Save the entity first with a dummy Razorpay ID to get the JVM UUID
        // without violating the NOT NULL constraint if Razorpay fails.
        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setUserId(userId);
        paymentOrder.setTotalAmount(total);
        paymentOrder.setCurrency("INR");
        paymentOrder.setStatus(PaymentOrderStatus.CREATED);
        paymentOrder.setRazorpayOrderId("PENDING-" + UUID.randomUUID());
        PaymentOrder saved = paymentOrderRepository.save(paymentOrder);

        // 2. Call Razorpay with our actual DB ID
        String razorpayOrderId = razorpayClient.createOrder(total, "INR", saved.getId().toString());

        // 3. Update with the real Razorpay Order ID
        saved.setRazorpayOrderId(razorpayOrderId);
        paymentOrderRepository.save(saved);

        List<PaymentOrderLineAllocationDto> allocations = new ArrayList<>();
        for (int i = 0; i < orderIds.size(); i++) {
            PaymentOrderLine line = new PaymentOrderLine();
            line.setPaymentOrderId(saved.getId());
            line.setOrderId(orderIds.get(i));
            line.setAmount(orderAmounts.get(i));
            line.setStatus(PaymentOrderLineStatus.PENDING);
            paymentOrderLineRepository.save(line);

            allocations.add(PaymentOrderLineAllocationDto.builder()
                    .orderId(orderIds.get(i))
                    .orderNumber("Order " + (i + 1))
                    .amount(orderAmounts.get(i))
                    .build());
        }

        log.info("Payment hold created: paymentOrderId={} razorpayOrderId={} total={} orders={}",
                saved.getId(), razorpayOrderId, total, orderIds.size());

        return PaymentHoldResponseDto.builder()
                .paymentOrderId(saved.getId())
                .razorpayOrderId(razorpayOrderId)
                .razorpayKeyId(razorpayProperties.getKeyId())
                .currency("INR")
                .totalAmount(total)
                .allocations(allocations)
                .build();
    }

    @Override
    @Transactional
    public PaymentHoldResponseDto createTopupHold(UUID userId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_TOPUP_AMOUNT", "Top-up amount must be greater than zero",
                    HttpStatus.BAD_REQUEST);
        }

        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setUserId(userId);
        paymentOrder.setTotalAmount(amount);
        paymentOrder.setCurrency("INR");
        paymentOrder.setStatus(PaymentOrderStatus.CREATED);
        paymentOrder.setTopup(true);
        PaymentOrder saved = paymentOrderRepository.save(paymentOrder);

        String razorpayOrderId = razorpayClient.createOrder(amount, "INR", saved.getId().toString());
        saved.setRazorpayOrderId(razorpayOrderId);
        paymentOrderRepository.save(saved);

        return PaymentHoldResponseDto.builder()
                .paymentOrderId(saved.getId())
                .razorpayOrderId(razorpayOrderId)
                .razorpayKeyId(razorpayProperties.getKeyId())
                .currency("INR")
                .totalAmount(amount)
                .allocations(new ArrayList<>()) // No specific orders allocated
                .build();
    }

    @Override
    @Transactional
    public void verifyPayment(UUID userId, String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
        // 1. Verify HMAC-SHA256 signature (Razorpay Checkout.js convention)
        boolean signatureValid = razorpayClient.verifyPaymentSignature(razorpayOrderId, razorpayPaymentId,
                razorpaySignature);
        if (!signatureValid) {
            throw new BusinessException("PAYMENT_SIGNATURE_INVALID",
                    "Payment signature verification failed -- this request may be forged", HttpStatus.BAD_REQUEST);
        }

        // 2. Cross-check with Razorpay (never trust frontend alone)
        RazorpayPaymentStatus gatewayStatus = razorpayClient.fetchPaymentStatus(razorpayPaymentId);
        if (!gatewayStatus.isAuthorized()) {
            throw new BusinessException("PAYMENT_NOT_AUTHORIZED",
                    "Payment is not in authorized state on Razorpay (status: " + gatewayStatus.status() + ")",
                    HttpStatus.BAD_REQUEST);
        }

        // 3. Update our PaymentOrder
        PaymentOrder paymentOrder = paymentOrderRepository.findByRazorpayOrderId(razorpayOrderId)
                .orElseThrow(() -> new BusinessException("PAYMENT_ORDER_NOT_FOUND",
                        "No payment order found for razorpayOrderId: " + razorpayOrderId, HttpStatus.NOT_FOUND));

        // Ownership check. The sibling getPaymentStatus() already scopes its
        // lookup to the requesting user, so the absence here was an inconsistency
        // rather than a design choice: the HMAC check above proves the caller
        // holds the secret key, NOT that the payment belongs to them. Any
        // authenticated customer who learned or guessed another customer's
        // razorpayOrderId could drive that order into AUTHORIZED. The ids are
        // sequential and not treated as secrets anywhere.
        if (!paymentOrder.getUserId().equals(userId)) {
            log.warn("verifyPayment: userId={} attempted to authorize paymentOrderId={} owned by userId={}",
                    userId, paymentOrder.getId(), paymentOrder.getUserId());
            throw new BusinessException("PAYMENT_ORDER_NOT_FOUND",
                    "No payment order found for razorpayOrderId: " + razorpayOrderId, HttpStatus.NOT_FOUND);
        }

        if (paymentOrder.getStatus().isTerminal()) {
            log.warn("verifyPayment called on already-terminal PaymentOrder {} (status={}), ignoring",
                    paymentOrder.getId(), paymentOrder.getStatus());
            return;
        }

        paymentOrder.setRazorpayPaymentId(razorpayPaymentId);
        paymentOrder.setStatus(PaymentOrderStatus.AUTHORIZED);
        paymentOrder.setAuthorizedAt(Instant.now());
        paymentOrderRepository.save(paymentOrder);

        log.info("Payment verified and authorized: paymentOrderId={} razorpayPaymentId={}", paymentOrder.getId(),
                razorpayPaymentId);

        if (!paymentOrder.isTopup()) {
            notifyCandidateVendorsIfOrders(paymentOrder.getId());
        }

        // If it's a top-up, we auto-capture immediately (no vendor acceptance needed)
        if (paymentOrder.isTopup()) {
            log.info("Auto-capturing top-up paymentOrderId={}", paymentOrder.getId());
            razorpayClient.capturePayment(razorpayPaymentId, paymentOrder.getTotalAmount(), paymentOrder.getCurrency());

            paymentOrder.setStatus(PaymentOrderStatus.CAPTURED);
            paymentOrder.setCapturedAmount(paymentOrder.getTotalAmount());
            paymentOrder.setCapturedAt(Instant.now());
            paymentOrderRepository.save(paymentOrder);

            walletService.credit(
                    paymentOrder.getUserId(),
                    paymentOrder.getTotalAmount(),
                    WalletTransactionReason.WALLET_TOP_UP,
                    paymentOrder.getId(),
                    "Wallet top-up via Razorpay");
            notifyPaymentSuccess(paymentOrder.getUserId(), paymentOrder.getId(), paymentOrder.getTotalAmount());
            log.info("Top-up complete. Wallet credited for paymentOrderId={}", paymentOrder.getId());
        }
    }

    @Override
    @Transactional
    public void onOrderAccepted(UUID orderId) {
        Optional<PaymentOrderLine> lineOpt = paymentOrderLineRepository.findByOrderId(orderId);
        if (lineOpt.isEmpty()) {
            // Payment integration not active for this order (e.g. legacy/test order)
            log.debug("onOrderAccepted: no payment line for orderId={}, skipping", orderId);
            return;
        }

        PaymentOrderLine line = lineOpt.get();
        if (line.getStatus() != PaymentOrderLineStatus.PENDING) {
            log.warn("onOrderAccepted: line for orderId={} is already {} -- idempotent no-op", orderId,
                    line.getStatus());
            return;
        }

        line.setStatus(PaymentOrderLineStatus.ACCEPTED);
        line.setResolvedAt(Instant.now());
        paymentOrderLineRepository.save(line);
        log.info("PaymentOrderLine ACCEPTED for orderId={}", orderId);

        tryCaptureIfBatchComplete(line.getPaymentOrderId());
    }

    @Override
    @Transactional
    public void onOrderVoided(UUID orderId) {
        Optional<PaymentOrderLine> lineOpt = paymentOrderLineRepository.findByOrderId(orderId);
        if (lineOpt.isEmpty()) {
            log.debug("onOrderVoided: no payment line for orderId={}, skipping", orderId);
            return;
        }

        PaymentOrderLine line = lineOpt.get();
        if (line.getStatus() != PaymentOrderLineStatus.PENDING) {
            log.warn("onOrderVoided: line for orderId={} is already {} -- idempotent no-op", orderId, line.getStatus());
            return;
        }

        line.setStatus(PaymentOrderLineStatus.VOIDED);
        line.setResolvedAt(Instant.now());
        paymentOrderLineRepository.save(line);
        log.info("PaymentOrderLine VOIDED for orderId={}", orderId);

        tryCaptureIfBatchComplete(line.getPaymentOrderId());
    }

    @Override
    @Transactional
    public void onOrderCancelled(UUID orderId) {
        // Every cancellation path (customer cancel, vendor rejection, accept timeout, failed payment) ends here, so
        // this is the one place the coupon share of the order is given back. Idempotent; a no-op without a coupon.
        if (couponService != null) { couponService.onOrderCancelled(orderId); }

        Optional<PaymentOrderLine> lineOpt = paymentOrderLineRepository.findByOrderId(orderId);
        if (lineOpt.isEmpty()) {
            log.debug("onOrderCancelled: no payment line for orderId={}, skipping", orderId);
            return;
        }

        PaymentOrderLine line = lineOpt.get();
        PaymentOrder batch = paymentOrderRepository.findByIdForUpdate(line.getPaymentOrderId())
                .orElseThrow(() -> new BusinessException("PAYMENT_ORDER_NOT_FOUND", "Parent payment order not found"));

        boolean batchCaptured = batch.getStatus() == PaymentOrderStatus.CAPTURED
                || batch.getStatus() == PaymentOrderStatus.PARTIALLY_CAPTURED;

        if (!batchCaptured) {
            // Not yet captured — void this line (no money was ever taken)
            onOrderVoided(orderId);
        } else {
            // Already captured — credit refund back to customer wallet
            if (line.getStatus() == PaymentOrderLineStatus.ACCEPTED
                    || line.getStatus() == PaymentOrderLineStatus.PENDING) {
                line.setStatus(PaymentOrderLineStatus.CANCELLED_REFUNDED);
                line.setResolvedAt(Instant.now());
                paymentOrderLineRepository.save(line);

                walletService.credit(batch.getUserId(), line.getAmount(),
                        WalletTransactionReason.ORDER_CANCELLED_POST_CAPTURE_REFUND,
                        orderId, "Refund for cancelled order (post-capture)");
                log.info("Post-capture refund credited: userId={} amount={} orderId={}",
                        batch.getUserId(), line.getAmount(), orderId);
            }
        }
    }

    @Override
    @Transactional
    public void onDeliveryCompleted(UUID orderId, BigDecimal vendorAmount, BigDecimal deliveryFee,
            BigDecimal platformAmount, UUID vendorUserId, UUID deliveryPartnerUserId) {
        // The caller works every amount out from what was saved on the ORDER at checkout (vendor price snapshot,
        // platform fee, coupon discount; see DeliverySettlementMath), so a later change of the admin's settings
        // cannot alter an old order: vendor = vendor price x qty, rider = delivery fee, platform = fee + margin
        // - coupon (never negative). A zero amount means "nothing to credit" and is skipped, never an error.

        if (vendorUserId == null) {
            // Not expected: an order is only dispatched for delivery after a vendor has accepted it.
            // If it ever happens (e.g. the shop was removed mid-order), make it loud instead of silent.
            log.error("Settlement: no vendor user resolved for orderId={} -- the vendor share ({}) was NOT credited",
                    orderId, vendorAmount);
        } else {
            creditIfPositive(vendorUserId, vendorAmount,
                    WalletTransactionReason.ORDER_VENDOR_SETTLEMENT,
                    orderId, "Revenue from completed order");
        }

        if (deliveryPartnerUserId != null) {
            creditIfPositive(deliveryPartnerUserId, deliveryFee,
                    WalletTransactionReason.ORDER_DELIVERY_SETTLEMENT,
                    orderId, "Delivery earnings for completed order (₹" + deliveryFee + ")");
        }

        creditIfPositive(WalletService.PLATFORM_WALLET_USER_ID, platformAmount,
                WalletTransactionReason.ORDER_PLATFORM_COMMISSION,
                orderId, "Platform earning (₹" + platformAmount + ": fee + margin - coupon) on completed order");

        log.info("Settlement complete: orderId={} vendorShare={} deliveryFee={} platformShare={}",
                orderId, vendorAmount, deliveryFee, platformAmount);
    }

    /**
     * WalletService.credit rejects non-positive amounts, so crediting a zero amount (e.g. the admin
     * set the delivery fee or platform fee to 0) would fail -- and roll back -- the whole delivery
     * completion. A zero or missing amount simply means "nothing to credit".
     */
    private void creditIfPositive(UUID userId, BigDecimal amount, WalletTransactionReason reason,
            UUID orderId, String description) {
        if (amount == null || amount.signum() <= 0) {
            log.info("Settlement: skipping zero-amount {} credit for orderId={}", reason, orderId);
            return;
        }
        walletService.credit(userId, amount, reason, orderId, description);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called after every line resolution. If all lines for the batch have resolved,
     * decides whether to capture (ACCEPTED lines exist) or mark as voided (all
     * VOIDED).
     * Uses a row-level lock on the PaymentOrder to prevent two concurrent line
     * resolutions from both triggering capture.
     */
    private void tryCaptureIfBatchComplete(UUID paymentOrderId) {
        PaymentOrder batch = paymentOrderRepository.findByIdForUpdate(paymentOrderId).orElse(null);
        if (batch == null || batch.getStatus().isTerminal()) {
            return;
        }

        List<PaymentOrderLine> allLines = paymentOrderLineRepository.findByPaymentOrderId(paymentOrderId);
        boolean anyPending = allLines.stream().anyMatch(l -> l.getStatus() == PaymentOrderLineStatus.PENDING);
        if (anyPending) {
            return; // Batch not complete yet -- another vendor still needs to accept/reject
        }

        List<PaymentOrderLine> acceptedLines = allLines.stream()
                .filter(l -> l.getStatus() == PaymentOrderLineStatus.ACCEPTED)
                .collect(Collectors.toList());

        if (acceptedLines.isEmpty()) {
            // Every line was voided -- nothing to capture
            batch.setStatus(PaymentOrderStatus.VOIDED);
            paymentOrderRepository.save(batch);
            log.info("All lines voided for paymentOrderId={} -- no capture needed", paymentOrderId);
            return;
        }

        // At least one accepted -- capture the sum of accepted lines
        if (batch.getRazorpayPaymentId() == null) {
            log.warn("Batch {} ready to capture but razorpayPaymentId is null (customer may not have verified yet)",
                    paymentOrderId);
            return;
        }

        BigDecimal captureAmount = acceptedLines.stream()
                .map(PaymentOrderLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        try {
            RazorpayPaymentStatus captured = razorpayClient.capturePayment(
                    batch.getRazorpayPaymentId(), captureAmount, batch.getCurrency());

            batch.setCapturedAmount(captureAmount);
            batch.setCapturedAt(Instant.now());
            batch.setStatus(acceptedLines.size() == allLines.size()
                    ? PaymentOrderStatus.CAPTURED
                    : PaymentOrderStatus.PARTIALLY_CAPTURED);
            paymentOrderRepository.save(batch);

            notifyPaymentSuccess(batch.getUserId(), batch.getId(), captureAmount);

            log.info("Payment captured: paymentOrderId={} capturedAmount={} status={}",
                    paymentOrderId, captureAmount, batch.getStatus());
        } catch (Exception e) {
            log.error("Razorpay capture FAILED for paymentOrderId={}: {}", paymentOrderId, e.getMessage(), e);
            batch.setStatus(PaymentOrderStatus.FAILED);
            paymentOrderRepository.save(batch);
            notifyPaymentFailed(batch.getUserId(), batch.getId(), e.getMessage());
        }
    }

    private void notifyPaymentSuccess(UUID customerUserId, UUID paymentOrderId, BigDecimal amount) {
        notificationService.send(customerUserId, NotificationRecipientRole.CUSTOMER, NotificationType.PAYMENT_SUCCESS,
                "Payment successful", "Your payment of \u20B9" + amount + " was successful",
                "{\"paymentOrderId\":\"" + paymentOrderId + "\",\"amount\":" + amount + "}");
    }

    private void notifyPaymentFailed(UUID customerUserId, UUID paymentOrderId, String reason) {
        notificationService.send(customerUserId, NotificationRecipientRole.CUSTOMER, NotificationType.PAYMENT_FAILED,
                "Payment failed", "Your payment could not be completed" + (reason != null ? " — try again" : ""),
                "{\"paymentOrderId\":\"" + paymentOrderId + "\",\"reason\":\"" + (reason != null ? reason : "")
                        + "\"}");
    }

    private void notifyCandidateVendorsIfOrders(UUID paymentOrderId) {
        List<PaymentOrderLine> lines = paymentOrderLineRepository.findByPaymentOrderId(paymentOrderId);
        for (PaymentOrderLine line : lines) {
            orderRepository.findById(line.getOrderId()).ifPresent(order -> {
                if (order.getCandidateVendorIds() != null) {
                    order.getCandidateVendorIds().forEach(shopId ->
                            shopLookupService.findOwnerUserIdByShopId(shopId)
                                    .ifPresent(ownerId -> notificationService.send(
                                            ownerId,
                                            NotificationRecipientRole.VENDOR,
                                            NotificationType.NEW_ORDER_REQUEST,
                                            "New order request",
                                            "Order " + order.getOrderNumber() + " is awaiting your shop's decision",
                                            orderData(order))));
                }
            });
        }
    }

    private String orderData(Order order) {
        return "{\"orderId\":\"" + order.getId() + "\",\"orderNumber\":\"" + order.getOrderNumber() + "\"}";
    }
}