package com.veggofresh.coupon.dto;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * What the held coupon would give each cart IF THAT CART WERE CHECKED OUT ALONE (the cart screen's number). The
 * checkout summary is the final truth: carts paid together are valued together.
 *
 * @param code           the held coupon, or null when none / no longer valid
 * @param message        why the held coupon is not valid any more, or null
 * @param discountByCart per cart: the discount when alone (zero when none)
 * @param messageByCart  per cart: a hint such as "Add Rs 100 more to get this discount" (absent when none)
 */
public record CouponPreview(String code, String message, Map<UUID, BigDecimal> discountByCart,
                            Map<UUID, String> messageByCart) {

    public static CouponPreview none() {
        return new CouponPreview(null, null, Collections.emptyMap(), Collections.emptyMap());
    }

    public BigDecimal discountOf(UUID cartKey) {
        BigDecimal d = discountByCart.get(cartKey);
        return d == null ? BigDecimal.ZERO : d;
    }

    public String messageOf(UUID cartKey) {
        String m = messageByCart.get(cartKey);
        return m != null ? m : message;
    }
}
