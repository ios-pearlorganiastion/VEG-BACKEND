package com.veggofresh.payment.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The result of {@link PaymentAuthorizedHandler#placeOrders}: either the orders that were created, or the reason
 * they could not be.
 */
public record PlacementOutcome(List<PlacedOrder> orders, String failureReason) {

    /** One order that was created, and what it costs (this becomes its payment line). */
    public record PlacedOrder(UUID orderId, BigDecimal amount) {
    }

    public static PlacementOutcome placed(List<PlacedOrder> orders) {
        return new PlacementOutcome(List.copyOf(orders), null);
    }

    public static PlacementOutcome rejected(String reason) {
        return new PlacementOutcome(List.of(), reason);
    }

    public boolean isPlaced() {
        return failureReason == null;
    }
}
