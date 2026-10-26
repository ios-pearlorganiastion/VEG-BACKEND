package com.veggofresh.coupon.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupee formatting for customer-facing messages: whole amounts without decimals ("Rs 100"), others with two. */
final class Money {

    private Money() {
    }

    static String inr(BigDecimal amount) {
        BigDecimal v = amount.setScale(2, RoundingMode.HALF_UP);
        if (v.stripTrailingZeros().scale() <= 0) {
            return "\u20B9" + v.setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        }
        return "\u20B9" + v.toPlainString();
    }
}
