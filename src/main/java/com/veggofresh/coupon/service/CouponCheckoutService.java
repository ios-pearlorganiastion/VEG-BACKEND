package com.veggofresh.coupon.service;

import com.veggofresh.coupon.dto.CouponPreview;
import com.veggofresh.coupon.dto.CouponQuote;
import com.veggofresh.coupon.dto.CouponReservation;
import com.veggofresh.coupon.dto.CustomerCouponDto;
import com.veggofresh.coupon.service.CouponMath.Basis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The customer-facing side of coupons: the offers list, holding a coupon, valuing it for the carts being paid,
 * taking it at place-order time, and giving it back when orders are cancelled.
 *
 * <p>A coupon belongs to the CUSTOMER, not to a cart. Holding one only checks the rules that do not depend on the
 * amount; what it is worth is decided when the carts being paid are known ({@link #quote} for the summary,
 * {@link #reserve} when the order is placed).
 */
public interface CouponCheckoutService {

    /** GET /api/customer/coupons: listed coupons, whether this customer can use each now, and why not. */
    List<CustomerCouponDto> listOffers(UUID userId);

    /**
     * Holds {@code code} for this customer (replacing any coupon held before). Throws a BusinessException with a
     * specific code when it can never work for this customer: INVALID_PROMO_CODE, COUPON_NOT_STARTED,
     * COUPON_EXPIRED, COUPON_NOT_FOR_YOU, COUPON_USAGE_LIMIT_REACHED, COUPON_BUDGET_EXHAUSTED,
     * COUPON_CUSTOMER_LIMIT_REACHED. The minimum order is NOT checked here.
     */
    void select(UUID userId, String code);

    /** Lets go of the held coupon (no-op when none). */
    void clearSelection(UUID userId);

    /**
     * Which of these codes could this customer apply right now (on, started, not ended, allowed for them, uses and
     * budget left, their own limit not reached)? Codes that do not exist are left out. The minimum order is not
     * considered. Used to hide banners that advertise a coupon the customer cannot use.
     */
    java.util.Set<String> usableCodes(UUID userId, java.util.Collection<String> codes);

    /** Is this customer holding a coupon? One cheap query, so screens can skip the pricing work when not. */
    boolean holdsCoupon(UUID userId);

    /** The held coupon's worth for each cart if it were paid alone (cart screen). Read-only, never throws. */
    CouponPreview previewEach(UUID userId, List<Basis> carts);

    /**
     * What the coupon is worth for exactly these carts paid together (checkout summary). Read-only, never throws
     * for business reasons. {@code explicitCode} (optional) is used instead of the held coupon.
     */
    CouponQuote quote(UUID userId, String explicitCode, List<Basis> carts);

    /**
     * Place-order: re-checks everything from scratch under a lock on the coupon and records the use, in the
     * caller's transaction. Throws a BusinessException when the coupon is dead (nothing is charged, the customer
     * gets a clear message). Returns no discount, without error, when the coupon just does not fit these carts.
     */
    CouponReservation reserve(UUID userId, String explicitCode, List<Basis> carts);

    /** Attaches the created orders' shares (orderId -> discount) to the reservation. Sum must equal its total. */
    void linkOrders(UUID redemptionId, Map<UUID, BigDecimal> shareByOrderId);

    /**
     * An order was cancelled / voided / rejected / its payment failed: its share goes back (budget) and, when no
     * order of the checkout keeps a share, the use is given back. Idempotent; no-op for orders without a coupon.
     */
    void onOrderCancelled(UUID orderId);
}
