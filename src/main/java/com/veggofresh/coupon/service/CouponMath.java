package com.veggofresh.coupon.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ONE place a coupon discount is calculated (pure function: no database, no clock, easy to test).
 *
 * <pre>
 *   base      = sum of the items totals of the carts being paid (delivery and platform fee are never discounted)
 *   raw       = PERCENT: base x value / 100, rounded to 2 decimals HALF_UP      FLAT: value
 *   discount  = min( raw, maxDiscount, base, sum of the carts' margins, remaining budget )
 * </pre>
 *
 * The margin limit is what makes a coupon unable to lose money: margin = customer price - vendor price, and the
 * vendor is always paid the vendor price. The discount is then shared between the orders in proportion to their
 * margins, so no order is ever given more than its own margin.
 */
public final class CouponMath {

    private CouponMath() {
    }

    /** One cart (= one future order) of a checkout: what it sells for and what the platform earns on it. */
    public record Basis(UUID key, BigDecimal items, BigDecimal margin) {
        public Basis {
            items = items == null ? BigDecimal.ZERO : items;
            margin = margin == null || margin.signum() < 0 ? BigDecimal.ZERO : margin;
        }
    }

    /** Which limit decided the final amount (for messages). */
    public enum Limit { NONE, MAX_DISCOUNT, ITEMS_TOTAL, MARGIN, BUDGET }

    public record Result(BigDecimal total, Map<UUID, BigDecimal> shares, Limit limitedBy) {
    }

    /**
     * @param percent         true = percentage coupon, false = flat amount
     * @param value           percent (0-100) or flat rupees
     * @param maxDiscount     cap, or null for none
     * @param remainingBudget what is left of the coupon's total budget, or null for unlimited
     */
    public static Result compute(boolean percent, BigDecimal value, BigDecimal maxDiscount,
                                 BigDecimal remainingBudget, List<Basis> carts) {
        Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal marginTotal = BigDecimal.ZERO;
        for (Basis b : carts) {
            shares.put(b.key(), zero());
            base = base.add(b.items());
            marginTotal = marginTotal.add(b.margin());
        }
        base = money(base);
        marginTotal = money(marginTotal);

        BigDecimal raw = percent
                ? base.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : money(value);
        BigDecimal discount = raw;
        Limit limit = Limit.NONE;

        if (maxDiscount != null && discount.compareTo(money(maxDiscount)) > 0) {
            discount = money(maxDiscount);
            limit = Limit.MAX_DISCOUNT;
        }
        if (discount.compareTo(base) > 0) {
            discount = base;
            limit = Limit.ITEMS_TOTAL;
        }
        if (discount.compareTo(marginTotal) > 0) {
            discount = marginTotal;
            limit = Limit.MARGIN;
        }
        if (remainingBudget != null) {
            BigDecimal left = remainingBudget.signum() < 0 ? zero() : money(remainingBudget);
            if (discount.compareTo(left) > 0) {
                discount = left;
                limit = Limit.BUDGET;
            }
        }
        if (discount.signum() <= 0) {
            return new Result(zero(), shares, limit);
        }
        allocate(discount, carts, marginTotal, shares);
        return new Result(discount, shares, limit);
    }

    /**
     * Splits {@code total} between the carts in proportion to their margins, in whole paise: each share is rounded
     * down, then the leftover paise go (one each) to the carts that lost the most to rounding, so the shares add up
     * to {@code total} exactly and no share ever exceeds its cart's margin.
     *
     * <p>Precondition: total &lt;= marginTotal (guaranteed by {@link #compute}).
     */
    static void allocate(BigDecimal total, List<Basis> carts, BigDecimal marginTotal, Map<UUID, BigDecimal> out) {
        BigInteger totalP = paise(total);
        BigInteger marginP = paise(marginTotal);
        int n = carts.size();
        BigInteger[] floor = new BigInteger[n];
        BigInteger[] rem = new BigInteger[n];
        BigInteger assigned = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger num = totalP.multiply(paise(carts.get(i).margin()));
            BigInteger[] qr = num.divideAndRemainder(marginP);
            floor[i] = qr[0];
            rem[i] = qr[1];
            assigned = assigned.add(qr[0]);
        }
        int leftover = totalP.subtract(assigned).intValueExact();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        // Biggest rounding loss first; on a tie the bigger margin; on a tie the earlier cart.
        order.sort(Comparator
                .<Integer, BigInteger>comparing(i -> rem[i]).reversed()
                .thenComparing(i -> paise(carts.get(i).margin()), Comparator.reverseOrder())
                .thenComparing(i -> i));
        for (int k = 0; k < leftover; k++) {
            floor[order.get(k)] = floor[order.get(k)].add(BigInteger.ONE);
        }
        for (int i = 0; i < n; i++) {
            out.put(carts.get(i).key(), new BigDecimal(floor[i]).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY));
        }
    }

    private static BigInteger paise(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toBigIntegerExact();
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
