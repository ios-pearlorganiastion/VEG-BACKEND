package com.veggofresh.customer.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * PHASE 2 — one of possibly several open carts for the customer. Every
 * mutating cart endpoint now returns the full list of these (see
 * CustomerCartController) so the client can render "Cart 1 / Cart 2 / ..."
 * (PROJECT_STATE section 2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartResponseDto {
    private UUID id;
    private UUID userId;
    /** Display label while shopping, e.g. "Cart 1" — derived from creation order. */
    private String cartLabel;
    private List<CartItemResponseDto> items;
    /** Sum of the prices of every currently purchasable line item, before fees. */
    private BigDecimal totalAmount;
    /**
     * Sum of line-item QUANTITIES among purchasable items — the same number the
     * badge reports. Not the number of line items: those disagree constantly
     * (2 lines at qty 2 and 5 is 2 lines but 7 items) and were the reason a
     * cart screen and its checkout summary showed different counts.
     */
    private int itemCount;
    private BigDecimal deliveryFee;
    private BigDecimal estimatedTax;
    /** totalAmount + deliveryFee + estimatedTax - promoDiscount, floored at zero. */
    private BigDecimal payableAmount;
    private String promoCode;
    private BigDecimal promoDiscount;
    /**
     * A hint about the held coupon for this cart, e.g. "Add Rs 100 more to get this discount, or check out together
     * with your other carts." Null when there is nothing to say. promoDiscount is what THIS cart gets if checked out
     * alone; the checkout summary has the final amounts for the carts actually paid together.
     */
    private String promoMessage;
    /**
     * Line items left in the cart whose product is no longer available. These
     * contribute to neither {@link #itemCount} nor {@link #totalAmount}, so the
     * client can explain to the customer why the count is lower than the number
     * of rows it is rendering, and offer to remove them.
     */
    private int unavailableItemCount;
}
