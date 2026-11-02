package com.veggofresh.customer.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a customer was buying when they tapped checkout, written next to the payment and read back when the
 * payment is authorized and the orders are created (see {@code OrderServiceImpl#placeOrders}).
 *
 * <p>Stored as JSON on the payment row. The payment module never looks inside it.
 *
 * @param addressId     the delivery address
 * @param cartIds       the carts that are being paid for (carts that were skipped at checkout are not here)
 * @param deliverySlotId optional slot
 * @param scheduledDate optional ISO date, as sent by the app
 * @param paymentMethodId as sent by the app, copied onto each order
 * @param couponCode    the coupon that was valued at checkout; pinned so the same coupon is used at placement
 * @param cartAmounts   cart id to what that cart's order costs, exactly as the customer was shown and charged.
 *                      The orders are only created if they come out at these amounts again.
 */
public record CheckoutSnapshot(
        UUID addressId,
        List<UUID> cartIds,
        UUID deliverySlotId,
        String scheduledDate,
        String paymentMethodId,
        String couponCode,
        Map<UUID, BigDecimal> cartAmounts) {
}
