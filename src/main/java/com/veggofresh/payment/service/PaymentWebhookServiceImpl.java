package com.veggofresh.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veggofresh.notification.entity.NotificationRecipientRole;
import com.veggofresh.notification.entity.NotificationType;
import com.veggofresh.notification.service.NotificationService;
import com.veggofresh.payment.client.RazorpayClient;
import com.veggofresh.payment.entity.PaymentOrder;
import com.veggofresh.payment.entity.PaymentOrderLine;
import com.veggofresh.payment.entity.PaymentOrderStatus;
import com.veggofresh.payment.entity.PaymentWebhookEvent;
import com.veggofresh.payment.repository.PaymentOrderLineRepository;
import com.veggofresh.payment.repository.PaymentOrderRepository;
import com.veggofresh.payment.repository.PaymentWebhookEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Razorpay webhook handler. Responsibilities:
 * 1. Verify the HMAC-SHA256 webhook signature (against the raw body).
 * 2. Deduplicate: every event is recorded by razorpay_event_id; a duplicate
 *    event (Razorpay retries on non-2xx) is silently ignored after the first.
 * 3. For {@code payment.authorized}: if the PaymentOrder hasn't been verified
 *    by the customer yet (e.g. Checkout.js callback was missed, or the app died right after
 *    paying), authorize it AND create the orders, exactly as verify() would. Orders only
 *    exist once a payment is authorized, so this is what places the order for a customer
 *    who paid and then lost connection.
 * 4. Unknown or irrelevant event types are still recorded for the audit trail
 *    but don't change any payment state.
 */
@Slf4j
@Service
public class PaymentWebhookServiceImpl implements PaymentWebhookService {

    private final RazorpayClient razorpayClient;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentOrderLineRepository paymentOrderLineRepository;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final PaymentService paymentService;

    /**
     * Optional: a failed payment has to reach the order module to cancel the
     * orders it was covering. Supplied by the customer module, so it is injected
     * leniently and checked for null at the call site -- a payment failure must
     * still be recorded even if that wiring is ever missing.
     */
    private final PaymentFailureHandler paymentFailureHandler;

    public PaymentWebhookServiceImpl(
            RazorpayClient razorpayClient,
            PaymentWebhookEventRepository webhookEventRepository,
            PaymentOrderRepository paymentOrderRepository,
            PaymentOrderLineRepository paymentOrderLineRepository,
            ObjectMapper objectMapper,
            NotificationService notificationService,
            PaymentService paymentService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            PaymentFailureHandler paymentFailureHandler) {
        this.razorpayClient = razorpayClient;
        this.webhookEventRepository = webhookEventRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentOrderLineRepository = paymentOrderLineRepository;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.paymentService = paymentService;
        this.paymentFailureHandler = paymentFailureHandler;
    }

    @Override
    @Transactional
    public void handleWebhook(String rawPayload, String razorpaySignatureHeader) {
        // 1. Signature verification -- MUST happen against the raw body
        if (!razorpayClient.verifyWebhookSignature(rawPayload, razorpaySignatureHeader)) {
            log.warn("Razorpay webhook signature invalid -- rejecting");
            throw new com.veggofresh.platform.exception.BusinessException(
                    "WEBHOOK_SIGNATURE_INVALID", "Webhook signature verification failed",
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }

        // 2. Parse event id and type
        String eventId;
        String eventType;
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            eventId = root.path("id").asText(null);
            eventType = root.path("event").asText(null);
        } catch (Exception e) {
            log.error("Failed to parse Razorpay webhook payload: {}", e.getMessage());
            throw new com.veggofresh.platform.exception.BusinessException(
                    "WEBHOOK_PARSE_ERROR", "Could not parse webhook payload",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }

        if (eventId == null || eventType == null) {
            log.warn("Razorpay webhook missing id or event type -- ignoring");
            return;
        }

        // 3. Idempotency: skip if we've seen this event before
        if (webhookEventRepository.existsByRazorpayEventId(eventId)) {
            log.info("Duplicate Razorpay webhook event {} -- already processed, skipping", eventId);
            return;
        }

        // 4. Record the event (even for unhandled types)
        PaymentWebhookEvent record = new PaymentWebhookEvent();
        record.setRazorpayEventId(eventId);
        record.setEventType(eventType);
        record.setPayload(rawPayload);
        webhookEventRepository.save(record);

        // 5. Dispatch to event-specific handler
        try {
            switch (eventType) {
                case "payment.authorized" -> handlePaymentAuthorized(rawPayload);
                case "payment.captured"   -> log.info("Webhook: payment.captured for event {} -- capture already handled by capture API call, no action needed", eventId);
                case "payment.failed"     -> handlePaymentFailed(rawPayload);
                default                   -> log.info("Webhook: unhandled event type '{}' recorded for audit trail", eventType);
            }
            // Mark as processed
            record.setProcessedAt(Instant.now());
            webhookEventRepository.save(record);
        } catch (Exception e) {
            log.error("Error processing webhook event {} (type={}): {}", eventId, eventType, e.getMessage(), e);
            // payment.authorized is the one event that must be retried: it is what creates the customer's
            // orders, and a failure there has rolled this whole transaction back (including the audit record
            // above), so redelivery starts clean. Everything else keeps the old behaviour: don't re-throw,
            // return 200 so Razorpay doesn't retry an event we received but could not act on.
            if ("payment.authorized".equals(eventType)) {
                throw e;
            }
        }
    }

    private void handlePaymentAuthorized(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode payment = root.path("payload").path("payment").path("entity");
            String razorpayOrderId = payment.path("order_id").asText(null);
            String razorpayPaymentId = payment.path("id").asText(null);

            if (razorpayOrderId == null || razorpayPaymentId == null) {
                log.warn("payment.authorized webhook missing order_id or payment id");
                return;
            }

            // Authorizes the payment and creates the orders, or does nothing when verify() already did.
            paymentService.onPaymentAuthorizedByGateway(razorpayOrderId, razorpayPaymentId);
        } catch (Exception e) {
            // Deliberately NOT swallowed any more. Placing the orders is part of this call, so a failure here
            // has already rolled the whole attempt back and the payment is still CREATED. Rethrowing makes the
            // webhook answer non-2xx, which is what makes Razorpay deliver the event again.
            log.error("Error handling payment.authorized webhook: {}", e.getMessage(), e);
            throw (e instanceof RuntimeException re) ? re : new IllegalStateException(e);
        }
    }

    private void handlePaymentFailed(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String razorpayOrderId = root.path("payload").path("payment").path("entity").path("order_id").asText(null);
            if (razorpayOrderId == null) return;

            paymentOrderRepository.findByRazorpayOrderId(razorpayOrderId).ifPresent(po -> {
                if (po.getStatus() == PaymentOrderStatus.CREATED) {
                    // One failed ATTEMPT is not a dead payment. Razorpay keeps the order open, so the customer
                    // can retry (wrong OTP, insufficient balance...) in the same popup and succeed. Nothing
                    // exists on our side to cancel yet, and marking the payment terminal here would make that
                    // later successful payment be ignored: the customer would pay and get no order.
                    notificationService.send(po.getUserId(), NotificationRecipientRole.CUSTOMER,
                            NotificationType.PAYMENT_FAILED,
                            "Payment failed", "Your payment could not be completed — please try again",
                            "{\"paymentOrderId\":\"" + po.getId() + "\"}");
                    log.info("PaymentOrder {}: a payment attempt failed; payment left open for a retry", po.getId());
                    return;
                }
                if (!po.getStatus().isTerminal()) {
                    po.setStatus(PaymentOrderStatus.FAILED);
                    paymentOrderRepository.save(po);
                    notificationService.send(po.getUserId(), NotificationRecipientRole.CUSTOMER, NotificationType.PAYMENT_FAILED,
                            "Payment failed", "Your payment could not be completed — please try again",
                            "{\"paymentOrderId\":\"" + po.getId() + "\"}");
                    log.warn("PaymentOrder {} marked FAILED via webhook", po.getId());

                    // The payment is dead, so every order this batch was covering
                    // has to go with it. Marking only the PaymentOrder FAILED left
                    // the N orders in PLACED -- a state a vendor can accept and
                    // delivery can complete -- so settlements were paid out for
                    // orders nobody had paid for, while the customer was told the
                    // payment had failed. Checkout fans one hold out over N orders,
                    // so this is N orders per failure, not one.
                    reconcileOrdersAfterFailure(po);
                }
            });
        } catch (Exception e) {
            log.error("Error handling payment.failed webhook: {}", e.getMessage(), e);
        }
    }

    /**
     * Tell the order side that this payment is dead, so it can cancel the orders
     * that can no longer be paid for.
     *
     * <p>Guarded in both directions. The handler itself is optional (this
     * callback is reached from a webhook thread and a failure to reach the order
     * module must not stop the payment state being recorded), and a throw from it
     * is caught and logged rather than allowed to escape into the webhook
     * dispatcher, which would discard the whole event.
     */
    private void reconcileOrdersAfterFailure(PaymentOrder paymentOrder) {
        if (paymentOrder.isTopup()) {
            log.info("Payment {} is a wallet top-up with no orders behind it -- nothing to reconcile",
                    paymentOrder.getId());
            return;
        }
        if (paymentFailureHandler == null) {
            log.error("No PaymentFailureHandler bean is wired -- payment {} is recorded FAILED but the orders it "
                    + "was covering stay in PLACED, so they can still be accepted, delivered and settled. "
                    + "This needs manual reconciliation.", paymentOrder.getId());
            return;
        }

        List<UUID> orderIds = paymentOrderLineRepository.findByPaymentOrderId(paymentOrder.getId()).stream()
                .map(PaymentOrderLine::getOrderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        try {
            paymentFailureHandler.onPaymentFailed(paymentOrder.getId(), paymentOrder.getUserId(), orderIds);
        } catch (Exception e) {
            log.error("Order-side reconciliation failed for payment {}: {}", paymentOrder.getId(), e.getMessage(), e);
        }
    }
}
