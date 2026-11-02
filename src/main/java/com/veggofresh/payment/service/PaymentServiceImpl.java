package com.veggofresh.payment.service;

import com.veggofresh.admin.service.PlatformSettingsService;
import com.veggofresh.coupon.service.CouponCheckoutService;
import com.veggofresh.notification.entity.NotificationRecipientRole;
import com.veggofresh.notification.entity.NotificationType;
import com.veggofresh.notification.service.NotificationService;
import com.veggofresh.payment.client.RazorpayClient;
import com.veggofresh.payment.client.RazorpayPaymentStatus;
import com.veggofresh.payment.dto.PaymentHoldResponseDto;
import com.veggofresh.payment.dto.PaymentOrderLineAllocationDto;
import com.veggofresh.payment.dto.PaymentVerifyResultDto;
import com.veggofresh.payment.entity.PaymentOrder;
import com.veggofresh.payment.entity.PaymentOrderLine;
import com.veggofresh.payment.entity.PaymentOrderLineStatus;
import com.veggofresh.payment.entity.PaymentOrderStatus;
import com.veggofresh.payment.repository.PaymentOrderLineRepository;
import com.veggofresh.payment.repository.PaymentOrderRepository;
import com.veggofresh.platform.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates Razorpay payment lifecycle: hold → verify → (orders created) →
 * capture/void → settle.
 *
 * Key design decisions:
 * 0. PAYMENT FIRST. Checkout creates only the payment plus a snapshot of what is being bought. The
 * Customer Orders (and their payment lines) are created in the same transaction that moves the payment
 * to AUTHORIZED, whichever of verify() or the webhook gets there first.
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
    /**
     * The order side, asked to create the orders once a payment is authorized. An ObjectProvider rather than
     * the bean itself: the customer module already depends on this class (checkout, cancel), so injecting
     * its implementation directly would make the two beans wait for each other at startup.
     */
    private final ObjectProvider<PaymentAuthorizedHandler> placementHandler;

    @Override
    @Transactional
    public PaymentHoldResponseDto createCheckoutHold(UUID userId, List<PaymentOrderLineAllocationDto> allocations,
            String checkoutSnapshot) {
        if (allocations == null || allocations.isEmpty()) {
            throw new BusinessException("PAYMENT_NO_ORDERS", "Cannot create a payment with nothing to pay for",
                    HttpStatus.BAD_REQUEST);
        }

        BigDecimal total = allocations.stream()
                .map(PaymentOrderLineAllocationDto::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1. Save the entity first with a dummy Razorpay ID to get the JVM UUID
        // without violating the NOT NULL constraint if Razorpay fails.
        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setUserId(userId);
        paymentOrder.setTotalAmount(total);
        paymentOrder.setCurrency("INR");
        paymentOrder.setStatus(PaymentOrderStatus.CREATED);
        paymentOrder.setRazorpayOrderId("PENDING-" + UUID.randomUUID());
        paymentOrder.setCheckoutSnapshot(checkoutSnapshot);
        PaymentOrder saved = paymentOrderRepository.save(paymentOrder);

        // 2. Call Razorpay with our actual DB ID
        String razorpayOrderId = razorpayClient.createOrder(total, "INR", saved.getId().toString());

        // 3. Update with the real Razorpay Order ID
        saved.setRazorpayOrderId(razorpayOrderId);
        paymentOrderRepository.save(saved);

        // No PaymentOrderLine yet: there is no order to point at. The lines are written when the payment is
        // authorized and the orders are created (see placeOrders).
        log.info("Checkout payment created: paymentOrderId={} razorpayOrderId={} total={} carts={}",
                saved.getId(), razorpayOrderId, total, allocations.size());

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
    public PaymentVerifyResultDto verifyPayment(UUID userId, String razorpayOrderId, String razorpayPaymentId,
            String razorpaySignature) {
        // 1. Verify HMAC-SHA256 signature (Razorpay Checkout.js convention)
        boolean signatureValid = razorpayClient.verifyPaymentSignature(razorpayOrderId, razorpayPaymentId,
                razorpaySignature);
        if (!signatureValid) {
            throw new BusinessException("PAYMENT_SIGNATURE_INVALID",
                    "Payment signature verification failed -- this request may be forged", HttpStatus.BAD_REQUEST);
        }

        // 2. Our PaymentOrder, and whose it is.
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

        // 3. Already handled? The webhook may have got here first, or the app is retrying a verify whose reply
        // it never received. Answer from our own state, WITHOUT asking Razorpay again: once the money is
        // captured the payment is no longer "authorized" at the gateway, which would turn a harmless retry
        // into an error.
        if (paymentOrder.getStatus() != PaymentOrderStatus.CREATED) {
            log.info("verifyPayment: paymentOrderId={} is already {} -- returning the existing result",
                    paymentOrder.getId(), paymentOrder.getStatus());
            return resultFor(paymentOrder);
        }

        // 4. Cross-check with Razorpay (never trust frontend alone)
        RazorpayPaymentStatus gatewayStatus = razorpayClient.fetchPaymentStatus(razorpayPaymentId);
        if (!gatewayStatus.isAuthorized()) {
            throw new BusinessException("PAYMENT_NOT_AUTHORIZED",
                    "Payment is not in authorized state on Razorpay (status: " + gatewayStatus.status() + ")",
                    HttpStatus.BAD_REQUEST);
        }

        // 5. Authorize, and create the orders.
        return authorizeAndPlaceOrders(paymentOrder.getId(), razorpayPaymentId);
    }

    @Override
    @Transactional
    public void onPaymentAuthorizedByGateway(String razorpayOrderId, String razorpayPaymentId) {
        Optional<PaymentOrder> paymentOrderOpt = paymentOrderRepository.findByRazorpayOrderId(razorpayOrderId);
        if (paymentOrderOpt.isEmpty()) {
            log.warn("payment.authorized: no PaymentOrder found for razorpayOrderId={}", razorpayOrderId);
            return;
        }
        PaymentOrder paymentOrder = paymentOrderOpt.get();
        if (paymentOrder.getStatus() != PaymentOrderStatus.CREATED) {
            log.info("PaymentOrder {} already in status {} -- webhook authorization is a no-op",
                    paymentOrder.getId(), paymentOrder.getStatus());
            return;
        }
        log.info("PaymentOrder {} authorized via webhook (the customer's verify call has not arrived)",
                paymentOrder.getId());
        authorizeAndPlaceOrders(paymentOrder.getId(), razorpayPaymentId);
    }

    /**
     * The one place a payment becomes AUTHORIZED, reached from both verify() and the webhook.
     *
     * <p>The row is locked first and its status re-read under the lock, so when verify and the webhook arrive
     * together exactly one of them does the work and the other sees the finished result. That is what keeps
     * the customer from getting two copies of every order.
     */
    private PaymentVerifyResultDto authorizeAndPlaceOrders(UUID paymentOrderId, String razorpayPaymentId) {
        PaymentOrder paymentOrder = paymentOrderRepository.findByIdForUpdate(paymentOrderId)
                .orElseThrow(() -> new BusinessException("PAYMENT_ORDER_NOT_FOUND",
                        "No payment order found: " + paymentOrderId, HttpStatus.NOT_FOUND));

        if (paymentOrder.getStatus() != PaymentOrderStatus.CREATED) {
            return resultFor(paymentOrder); // lost the race: the other path already did it
        }

        paymentOrder.setRazorpayPaymentId(razorpayPaymentId);
        paymentOrder.setStatus(PaymentOrderStatus.AUTHORIZED);
        paymentOrder.setAuthorizedAt(Instant.now());
        paymentOrderRepository.save(paymentOrder);

        log.info("Payment authorized: paymentOrderId={} razorpayPaymentId={}", paymentOrder.getId(),
                razorpayPaymentId);

        // If it's a top-up, we auto-capture immediately (no vendor acceptance needed)
        if (paymentOrder.isTopup()) {
            completeTopup(paymentOrder, razorpayPaymentId);
            return resultFor(paymentOrder);
        }

        placeOrders(paymentOrder);
        return resultFor(paymentOrder);
    }

    /**
     * Asks the order side to create the orders for this authorized payment. Either the orders and their
     * payment lines now exist, or the payment is marked PLACEMENT_FAILED, is never captured, and the customer
     * is told. An unexpected exception from the handler propagates on purpose: everything rolls back, the
     * payment stays CREATED, and the next verify call or webhook retry tries again.
     */
    private void placeOrders(PaymentOrder paymentOrder) {
        PaymentAuthorizedHandler handler = placementHandler.getObject();
        PlacementOutcome outcome = handler.placeOrders(paymentOrder.getId(), paymentOrder.getUserId(),
                paymentOrder.getCheckoutSnapshot(), paymentOrder.getTotalAmount());

        if (outcome.isPlaced()) {
            for (PlacementOutcome.PlacedOrder placed : outcome.orders()) {
                PaymentOrderLine line = new PaymentOrderLine();
                line.setPaymentOrderId(paymentOrder.getId());
                line.setOrderId(placed.orderId());
                line.setAmount(placed.amount());
                line.setStatus(PaymentOrderLineStatus.PENDING);
                paymentOrderLineRepository.save(line);
            }
            paymentOrder.setOrdersPlacedAt(Instant.now());
            paymentOrderRepository.save(paymentOrder);
            log.info("Orders placed for paymentOrderId={}: {} order(s)", paymentOrder.getId(),
                    outcome.orders().size());
            return;
        }

        String reason = outcome.failureReason();
        paymentOrder.setStatus(PaymentOrderStatus.PLACEMENT_FAILED);
        paymentOrder.setPlacementFailureReason(
                reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
        paymentOrderRepository.save(paymentOrder);
        log.warn("Payment {} was authorized but its orders could not be placed ({}). Nothing will be captured.",
                paymentOrder.getId(), reason);
        notificationService.send(paymentOrder.getUserId(), NotificationRecipientRole.CUSTOMER,
                NotificationType.PAYMENT_FAILED, "We couldn't place your order",
                "Your payment went through but your order could not be placed: " + reason
                        + ". You have not been charged; the amount on hold will be released.",
                "{\"paymentOrderId\":\"" + paymentOrder.getId() + "\"}");
    }

    private void completeTopup(PaymentOrder paymentOrder, String razorpayPaymentId) {
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

    private PaymentVerifyResultDto resultFor(PaymentOrder paymentOrder) {
        List<UUID> orderIds = paymentOrderLineRepository.findByPaymentOrderId(paymentOrder.getId()).stream()
                .map(PaymentOrderLine::getOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return PaymentVerifyResultDto.builder()
                .paymentOrderId(paymentOrder.getId())
                .status(paymentOrder.getStatus().name())
                .ordersPlaced(paymentOrder.getOrdersPlacedAt() != null)
                .orderIds(orderIds)
                .message(paymentOrder.getPlacementFailureReason())
                .build();
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
        couponService.onOrderCancelled(orderId);

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
}
