package com.veggofresh.payment.entity;

import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per Razorpay order, i.e. one row per checkout() call -- NOT one row
 * per Customer Order. OrderRequestDto's own doc comment is explicit: "one
 * payment can fan out into N independent orders" when a customer's open
 * carts span more than one vendor. The per-Order allocation and per-Order
 * accept/reject outcome live in {@link PaymentOrderLine}, one line per
 * Customer Order, all pointing back at this row.
 *
 * PAYMENT FIRST: a row is created at checkout with the customer's cart snapshot, and the Customer
 * Orders are only created once this payment is AUTHORIZED (see PaymentAuthorizedHandler).
 *
 * Money flow: created with payment_capture:0 (manual capture) so nothing is
 * charged at authorization time. Capture only happens once, for the sum of
 * whichever lines are ACCEPTED, once every line in the batch has resolved
 * (see PaymentServiceImpl) -- Razorpay does not support multiple sequential
 * captures against the same payment.
 */
@Entity
@Table(name = "payment_orders")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")
public class PaymentOrder extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "razorpay_order_id", nullable = false, length = 64)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 64)
    private String razorpayPaymentId;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    /** Sum of all lines at creation time -- what the customer is asked to pay on Razorpay's checkout screen. */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** Null until a capture call has actually been made. */
    @Column(name = "captured_amount", precision = 12, scale = 2)
    private BigDecimal capturedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentOrderStatus status = PaymentOrderStatus.CREATED;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "is_topup", nullable = false)
    private boolean isTopup = false;

    /**
     * What the customer was buying, written at checkout and handed back at authorization so the orders can
     * be created then. Opaque JSON owned by the customer module; null for wallet top-ups.
     */
    @Column(name = "checkout_snapshot", columnDefinition = "TEXT")
    private String checkoutSnapshot;

    /** Set once, in the transaction that creates the orders. Null until then. */
    @Column(name = "orders_placed_at")
    private Instant ordersPlacedAt;

    /** Why an authorized payment could not be turned into orders (status PLACEMENT_FAILED). */
    @Column(name = "placement_failure_reason", length = 500)
    private String placementFailureReason;
}
