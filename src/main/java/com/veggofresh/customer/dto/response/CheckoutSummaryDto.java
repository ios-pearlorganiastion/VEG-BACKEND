package com.veggofresh.customer.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * PHASE 2 — BREAKING CHANGE from the Phase 1 shape (which was one flat
 * subtotal/total for a single cart). Now a per-cart breakdown plus a grand
 * total, since one checkout call can produce N independent orders.
 *
 * <p>Every number here is defined to be IDENTICAL to the corresponding field on
 * the cart screen ({@code CartResponseDto}) for the same address. The two
 * responses are two views of one calculation, and they used to disagree on item
 * counts and on which cart carried which label.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutSummaryDto {
    private List<CartCheckoutBreakdownDto> carts;
    /** Sum of the per-cart item counts, each of which is a sum of quantities. */
    private int totalItemCount;
    private BigDecimal grandTotal;
    /**
     * Carts that will NOT be included when checkout is submitted — either their
     * items can no longer be fulfilled by a single vendor, or none of their
     * items are purchasable any more. Populated here (not just at checkout time)
     * so the customer sees a total that matches what they will actually be
     * charged, rather than a total that silently shrinks on submit.
     */
    private List<CheckoutIssueDto> issues;

    /** The coupon used for these carts (null when none applied). */
    private String promoCode;
    /** The whole discount of this checkout; each cart's share is in its breakdown. */
    private BigDecimal promoDiscount;
    /** NONE, APPLIED, NOT_APPLICABLE or INVALID. */
    private String promoStatus;
    /** A readable line for the customer, e.g. "Add Rs 100 more to get this discount". Null when nothing to say. */
    private String promoMessage;
}
