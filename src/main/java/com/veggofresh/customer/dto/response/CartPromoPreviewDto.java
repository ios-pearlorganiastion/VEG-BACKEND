package com.veggofresh.customer.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the customer's coupon is worth for a set of carts paid TOGETHER, for the cart screen.
 *
 * <p>The cart list ({@code GET /api/customer/carts}) values the coupon for each cart ALONE, so a cart below
 * the minimum shows nothing even though the carts together qualify. This is the combined view: it is valued
 * exactly the way the checkout summary and checkout value it (same calculation, same inputs), so the cart
 * screen, the summary and the payment agree for the same carts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartPromoPreviewDto {

    /** The coupon valued (held by the customer, or the explicit code asked about). Null when there is none. */
    private String promoCode;

    /**
     * {@code NONE} no coupon held; {@code APPLIED} gives a discount; {@code NOT_APPLICABLE} valid but gives
     * nothing for these carts (e.g. below the minimum): see {@link #message}; {@code INVALID} cannot be used
     * (expired, limit reached...): see {@link #message}.
     */
    private String status;

    /** Sum of the items of the carts valued (delivery and platform fee are never discounted). */
    private BigDecimal itemsSubtotal;

    /** The one discount for all these carts together. Zero unless {@code status} is APPLIED. */
    private BigDecimal discount;

    /** "Add ₹X more to get this discount (minimum order ₹Y)." and similar. Null when there is nothing to say. */
    private String message;

    /** How the discount is shared between the carts. Internal accounting: show {@link #discount}, not these. */
    private List<CartShare> perCart;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartShare {
        private UUID cartId;
        private BigDecimal discount;
    }
}
