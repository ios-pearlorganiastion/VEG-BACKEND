package com.veggofresh.payment.service;

import com.veggofresh.payment.dto.PaymentHoldResponseDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the full Razorpay payment lifecycle for an order checkout:
 * hold (order creation with payment_capture:0) → verify (customer pays on Razorpay
 * Checkout.js) → capture (vendor accepts) → void/refund (cancellation) → settle
 * (delivery complete, split to vendor/delivery/platform wallets).
 *
 * Called by Customer module (checkout, cancel) and Vendor module (accept triggers
 * capture via CustomerOrderService). Never imports Customer or Delivery @Entity classes.
 */
public interface PaymentService {

    /**
     * Called once per checkout() invocation (one Razorpay order for the combined total
     * of all N orders created from the multi-cart checkout). Creates a Razorpay order
     * with payment_capture:0 and a PaymentOrderLine per Customer Order.
     *
     * @param userId       the customer's user id
     * @param orderIds     the list of Customer Order ids created by checkout()
     * @param orderAmounts the corresponding total amounts (index-matched with orderIds)
     * @return hold details to embed in CheckoutResultDto so the frontend can open Razorpay Checkout.js
     */
    PaymentHoldResponseDto createHold(UUID userId, List<UUID> orderIds, List<BigDecimal> orderAmounts);

    /**
     * Called when the customer's Razorpay Checkout.js success callback posts back.
     * Verifies the HMAC-SHA256 signature, cross-checks payment status with Razorpay's
     * API (never trust the frontend alone), and marks the PaymentOrder as AUTHORIZED.
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
    void verifyPayment(UUID userId, String razorpayOrderId, String razorpayPaymentId, String razorpaySignature);

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
