package com.veggofresh.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Returned by {@code PaymentService.createCheckoutHold(...)}, called once per
 * checkout() invocation (not once per Order) -- carries everything the
 * frontend needs to open Razorpay's Checkout.js widget for the combined
 * total, plus the per-Order breakdown so the UI can show "this payment
 * covers these N orders".
 *
 * {@code CheckoutResultDto.paymentHold} (added in Phase 2.2) will embed
 * this directly in the checkout response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentHoldResponseDto {
    private UUID paymentOrderId;
    private String razorpayOrderId;
    /** Razorpay's publishable key id -- safe to expose to the frontend, needed to open Checkout.js. */
    private String razorpayKeyId;
    private String currency;
    private BigDecimal totalAmount;
    /** One entry per cart being paid for. The orders do not exist yet, so {@code orderId} is null. */
    private List<PaymentOrderLineAllocationDto> allocations;
}
