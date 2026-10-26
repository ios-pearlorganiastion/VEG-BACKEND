package com.veggofresh.coupon.dto;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * The result of taking a coupon at place-order time. {@code redemptionId} is null when no discount was given
 * (no coupon, or the coupon does not fit these carts); then {@code total} is zero and {@code shares} is empty.
 */
public record CouponReservation(UUID redemptionId, String code, BigDecimal total, Map<UUID, BigDecimal> shares,
                                String message) {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static CouponReservation none() {
        return new CouponReservation(null, null, ZERO, Collections.emptyMap(), null);
    }

    public static CouponReservation notApplied(String code, String message) {
        return new CouponReservation(null, code, ZERO, Collections.emptyMap(), message);
    }

    public boolean hasDiscount() {
        return redemptionId != null;
    }

    public BigDecimal shareOf(UUID cartKey) {
        BigDecimal s = shares.get(cartKey);
        return s == null ? ZERO : s;
    }
}
