package com.veggofresh.payment.service;

import com.veggofresh.payment.dto.PaymentHoldResponseDto;
import com.veggofresh.payment.dto.PaymentOrderLineAllocationDto;
import com.veggofresh.payment.dto.PaymentVerifyResultDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the full Razorpay payment lifecycle for an order checkout:
 * hold (order creation with payment_capture:0) → verify (customer pays on Razorpay
 * Checkout.js) → ORDERS ARE CREATED → capture (vendor accepts) → void/refund
 * (cancellation) → settle (delivery complete, split to vendor/delivery/platform wallets).
 *
 * <p>PAYMENT FIRST: no Customer Order exists before the payment is AUTHORIZED. Checkout only
 * creates the payment (and stores what is being bought); authorization, whether it is learned
 * from the customer's verify call or from the Razorpay webhook, is what creates the orders.
 *
 * Called by Customer module (checkout, cancel) and Vendor module (accept triggers
 * capture via CustomerOrderService). Never imports Customer or Delivery @Entity classes.
 */
public interface PaymentService {

    /**
     * Called once per checkout() invocation. Creates ONE Razorpay order (payment_capture:0) for the
     * combined total of everything being checked out, and stores the snapshot so the orders can be
     * created later. <b>No Customer Order and no payment line exists yet</b>: if the customer
     * dismisses the Razorpay popup, nothing was created and nothing needs cleaning up.
     *
     * @param userId           the customer's user id
     * @param allocations      one entry per cart being paid for (label and amount; {@code orderId} is
     *                         null because the order does not exist yet)
     * @param checkoutSnapshot what the customer is buying, opaque to this module; handed back to
     *                         {@link PaymentAuthorizedHandler} at authorization
     * @return hold details to embed in CheckoutResultDto so the frontend can open Razorpay Checkout.js
     */
    PaymentHoldResponseDto createCheckoutHold(UUID userId, List<PaymentOrderLineAllocationDto> allocations,
            String checkoutSnapshot);

    /**
     * Called when the customer's Razorpay Checkout.js success callback posts back.
     * Verifies the HMAC-SHA256 signature, cross-checks payment status with Razorpay's
     * API (never trust the frontend alone), marks the PaymentOrder as AUTHORIZED and
     * <b>creates the orders</b>.
     *
     * <p>Safe to call repeatedly, and safe alongside the webhook: the orders are created once, and every
     * later call returns the same result.
     *
     * @param razorpayOrderId   the Razorpay order id returned from createHold()
     * @param razorpayPaymentId the payment id from Razorpay's Checkout.js callback
     * @param razorpaySignature the HMAC-SHA256 signature from Razorpay's callback
     */
    /**
     * @param userId the authenticated caller; the payment must belong to them.
     *               The HMAC signature proves the caller holds the gateway
     *               secret, not that the payment is theirs, so ownership is
     *               checked separately. Not propagated from any client-supplied
     *               field.
     */
    PaymentVerifyResultDto verifyPayment(UUID userId, String razorpayOrderId, String razorpayPaymentId,
            String razorpaySignature);

    /**
     * The webhook's way of learning the same fact as {@link #verifyPayment}: Razorpay says this payment is
     * authorized. Covers the customer whose app died after paying and never called verify. The webhook
     * signature has already been checked by the caller. Does nothing if the payment was already handled.
     */
    void onPaymentAuthorizedByGateway(String razorpayOrderId, String razorpayPaymentId);

    /**
     * Called when a vendor accepts one of the orders in the batch. Marks the
     * corresponding PaymentOrderLine as ACCEPTED. If all lines in the batch have
     * now resolved, triggers the single Razorpay capture for the ACCEPTED lines' sum.
     *
     * @param orderId the Customer Order id that was just accepted
     */
    void onOrderAccepted(UUID orderId);

    /**
     * Called when an order is voided before capture (vendor rejects, or system-cancel
     * before any vendor accepted). Marks the corresponding PaymentOrderLine as VOIDED.
     * If all lines have now resolved and none were ACCEPTED, marks the PaymentOrder
     * as VOIDED -- no capture ever happens, Razorpay auto-releases the authorization.
     *
     * @param orderId the Customer Order id that was rejected / cancelled before capture
     */
    void onOrderVoided(UUID orderId);

    /**
     * Called on any order cancellation after the batch has already been captured.
     * Credits the order's amount back to the customer's wallet as a refund.
     * If the batch has NOT been captured yet, delegates to {@link #onOrderVoided}.
     *
     * @param orderId the Customer Order id being cancelled
     */
    void onOrderCancelled(UUID orderId);

    /**
     * Called when a delivery is completed successfully. Credits the vendor, delivery
     * partner and platform wallets.
     *
     * <p>Every amount passed in is the one that was fixed on the order at checkout (from
     * the admin's flat fee settings), so the credits always equal what the customer was
     * actually charged -- even if the admin changed a fee while the order was in progress.
     *
     * <p>A zero amount (for example the admin set a fee to 0) means "nothing to credit"
     * and is skipped; it does not fail the settlement.
     *
     * @param orderId               the delivered Customer Order id
     * @param vendorAmount          what the vendor is paid (admin-set vendor price x quantity), see DeliverySettlementMath
     * @param deliveryFee           the delivery fee charged on this order (goes to the delivery partner)
     * @param platformAmount        what the platform keeps: platform fee + margin - coupon discount (never negative)
     * @param vendorUserId          the shop owner's user id
     * @param deliveryPartnerUserId the delivery partner's user id
     */
    void onDeliveryCompleted(UUID orderId, BigDecimal vendorAmount, BigDecimal deliveryFee, BigDecimal platformAmount,
                             UUID vendorUserId, UUID deliveryPartnerUserId);

    /**
     * Called when a customer wants to manually add funds to their wallet.
     * Creates a Razorpay Order but no PaymentOrderLines (since there are no Customer Orders).
     */
    PaymentHoldResponseDto createTopupHold(UUID userId, BigDecimal amount);
}
