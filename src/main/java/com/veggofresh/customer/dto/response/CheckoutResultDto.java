package com.veggofresh.customer.dto.response;

import com.veggofresh.payment.dto.PaymentHoldResponseDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PHASE 2 — result of a multi-cart checkout: one payment can fan out into N
 * independent orders (PROJECT_STATE section 2). Any cart whose vendor
 * overlap broke between add-time and checkout-time is reported in
 * {@link #issues} and excluded from {@link #orders} rather than blocking
 * the rest of checkout.
 *
 * PAYMENT INTEGRATION: paymentHold carries the Razorpay order details
 * (razorpayOrderId, keyId, totalAmount) needed to open Checkout.js.
 * It is null only when all orders failed checkout (CHECKOUT_FAILED is thrown).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutResultDto {
    private List<OrderResponseDto> orders;
    private List<CheckoutIssueDto> issues;
    /** Razorpay payment hold — use razorpayOrderId + razorpayKeyId to open Checkout.js */
    private PaymentHoldResponseDto paymentHold;

    /** Set when a coupon was held/given but did not fit these carts, so no discount was applied. */
    private String promoMessage;
}
