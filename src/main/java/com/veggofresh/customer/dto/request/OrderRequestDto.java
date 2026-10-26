package com.veggofresh.customer.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * PHASE 2 (updated) — selective multi-cart checkout.
 *
 * {@code cartIds} is optional:
 *  - When provided (non-null, non-empty): only the listed carts are checked
 *    out in this call. The customer can place an order for Cart 1 alone,
 *    leaving Cart 2 open for a separate checkout later.
 *  - When null or empty: ALL of the customer's open carts are processed
 *    (original Phase-2 all-carts behaviour — preserved for backward compat).
 *
 * Address/payment/slot apply uniformly to every cart included in this call.
 */
@Getter
@Setter
public class OrderRequestDto {

    @NotNull(message = "Address ID is required")
    private UUID addressId;

    /**
     * Optional — specific cart IDs to check out.
     * Null or empty → all open carts (old behaviour).
     * Non-empty     → only the listed carts; others stay open.
     */
    private List<UUID> cartIds;

    /** Payment method label — e.g. "COD", "UPI", "ONLINE", "WALLET" */
    private String paymentMethodId;

    /** Optional — delivery slot selected at checkout */
    private UUID deliverySlotId;

    /** Optional — ISO date string for scheduled delivery */
    private String scheduledDate;

    /**
     * Optional. A coupon code to use for THIS checkout (typed on the checkout screen). When omitted, the coupon the
     * customer is holding (applied in the cart) is used. One coupon per checkout, whatever the number of carts.
     */
    private String couponCode;
}
