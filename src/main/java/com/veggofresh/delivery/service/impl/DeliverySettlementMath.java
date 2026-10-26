package com.veggofresh.delivery.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The money split of one delivered order, as a pure function (no database, easy to test).
 *
 * <pre>
 *   customer paid   = items + deliveryFee + platformFee - promoDiscount
 *   vendor gets     = sum(vendorUnitPrice x quantity)           (the admin-set vendor price)
 *   rider gets      = deliveryFee
 *   platform keeps  = platformFee + margin - promoDiscount      margin = items - vendor amount
 * </pre>
 *
 * vendor + rider + platform = what the customer paid, so the platform never pays out more than it collected,
 * as long as the discount was kept within the margin when the order was placed (the coupon engine does that).
 * If an old order (placed before vendor prices existed, so margin = 0) carries a discount, the platform share
 * is floored at 0 instead of going negative.
 */
public final class DeliverySettlementMath {

    private DeliverySettlementMath() {
    }

    /** One order line: customer unit price, vendor unit price snapshot (null on old orders) and quantity. */
    public record Line(BigDecimal price, BigDecimal vendorUnitPrice, int quantity) {
    }

    public record Result(BigDecimal itemsTotal, BigDecimal vendorAmount, BigDecimal margin,
                         BigDecimal platformAmount) {
    }

    public static Result compute(List<Line> lines, BigDecimal platformFee, BigDecimal promoDiscount) {
        BigDecimal items = BigDecimal.ZERO;
        BigDecimal vendor = BigDecimal.ZERO;
        if (lines != null) {
            for (Line l : lines) {
                if (l == null || l.price() == null) {
                    continue;
                }
                BigDecimal qty = BigDecimal.valueOf(l.quantity());
                BigDecimal unitVendor = l.vendorUnitPrice() == null || l.vendorUnitPrice().compareTo(l.price()) > 0
                        ? l.price() : l.vendorUnitPrice();
                items = items.add(l.price().multiply(qty));
                vendor = vendor.add(unitVendor.multiply(qty));
            }
        }
        items = items.setScale(2, RoundingMode.HALF_UP);
        vendor = vendor.setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = items.subtract(vendor);
        BigDecimal fee = platformFee == null ? BigDecimal.ZERO : platformFee;
        BigDecimal discount = promoDiscount == null ? BigDecimal.ZERO : promoDiscount;
        BigDecimal platform = fee.add(margin).subtract(discount).setScale(2, RoundingMode.HALF_UP);
        if (platform.signum() < 0) {
            platform = BigDecimal.ZERO.setScale(2);
        }
        return new Result(items, vendor, margin, platform);
    }
}
