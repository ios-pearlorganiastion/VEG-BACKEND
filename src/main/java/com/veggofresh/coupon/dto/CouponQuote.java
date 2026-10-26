package com.veggofresh.coupon.dto;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * What a coupon is worth for the carts about to be paid. Never thrown as an error: the checkout summary shows it,
 * and place-order decides what to do with each status.
 *
 * <ul>
 *   <li>NONE           - no coupon is held / given</li>
 *   <li>APPLIED        - a discount of {@code total}, shared per cart in {@code shares}</li>
 *   <li>NOT_APPLICABLE - the coupon is fine but does not fit THESE carts (minimum order, no margin): no discount,
 *                        the customer keeps the coupon</li>
 *   <li>INVALID        - the coupon is dead for this customer (unknown, off, expired, limit reached, not for them)</li>
 * </ul>
 */
public record CouponQuote(Status status, UUID couponId, String code, String errorCode, String message,
                          BigDecimal total, Map<UUID, BigDecimal> shares) {

    public enum Status { NONE, APPLIED, NOT_APPLICABLE, INVALID }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static CouponQuote none() {
        return new CouponQuote(Status.NONE, null, null, null, null, ZERO, Collections.emptyMap());
    }

    public static CouponQuote invalid(String code, String errorCode, String message) {
        return new CouponQuote(Status.INVALID, null, code, errorCode, message, ZERO, Collections.emptyMap());
    }

    public static CouponQuote notApplicable(UUID couponId, String code, String errorCode, String message) {
        return new CouponQuote(Status.NOT_APPLICABLE, couponId, code, errorCode, message, ZERO, Collections.emptyMap());
    }

    public static CouponQuote applied(UUID couponId, String code, String message, BigDecimal total,
                                      Map<UUID, BigDecimal> shares) {
        return new CouponQuote(Status.APPLIED, couponId, code, null, message, total, shares);
    }

    /** The discount of one cart (zero when it has no share). */
    public BigDecimal shareOf(UUID cartKey) {
        BigDecimal s = shares.get(cartKey);
        return s == null ? ZERO : s;
    }
}
