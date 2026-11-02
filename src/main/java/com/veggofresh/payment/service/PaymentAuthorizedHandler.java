package com.veggofresh.payment.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lets the payment module ask the order side to create the Customer Orders once a payment has been
 * AUTHORIZED.
 *
 * <h2>Why orders are created here and not at checkout</h2>
 *
 * <p>Checkout used to save the orders first and ask for payment afterwards. That made every order visible to
 * vendors (and acceptable, deliverable and settleable) before anybody had paid. Now checkout only prepares the
 * payment and stores a snapshot of what is being bought; the orders come into existence in the same
 * transaction that moves the payment to AUTHORIZED.
 *
 * <h2>Why this is an interface</h2>
 *
 * <p>Same reason as {@link PaymentFailureHandler}: the payment module must not depend on the customer module
 * (customer already depends on payment). The payment module declares the port, the customer module implements
 * it, and the snapshot travels as an opaque string the payment module never reads.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Called inside the payment module's transaction, with the payment row locked, at most once per payment.</li>
 *   <li>Expected problems (cart changed, price changed, coupon no longer valid) are RETURNED as
 *       {@link PlacementOutcome#rejected}, not thrown. A throw marks the surrounding transaction
 *       rollback-only, which would also undo the payment's own state change.</li>
 *   <li>An unexpected exception is allowed to propagate: everything rolls back, the payment stays CREATED and
 *       the next verify call or webhook retry tries again.</li>
 * </ul>
 */
public interface PaymentAuthorizedHandler {

    /**
     * Creates the orders for an authorized payment, or explains why it cannot.
     *
     * @param paymentOrderId  the payment that was just authorized
     * @param userId          the customer the payment belongs to
     * @param checkoutSnapshot what the customer was buying, exactly as the customer module wrote it at checkout
     * @param authorizedTotal what the customer actually paid; the orders must add up to exactly this
     */
    PlacementOutcome placeOrders(UUID paymentOrderId, UUID userId, String checkoutSnapshot,
            BigDecimal authorizedTotal);
}
