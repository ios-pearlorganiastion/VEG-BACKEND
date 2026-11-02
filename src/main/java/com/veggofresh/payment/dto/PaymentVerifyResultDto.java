package com.veggofresh.payment.dto;

import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the app gets back from the verify call. Orders are created when the payment is authorized, so this is
 * where the app learns their ids (checkout no longer returns any).
 *
 * <p>Calling verify again for the same payment is safe and returns the same orders: it never creates a second
 * set, which is what lets the app simply retry after a lost response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentVerifyResultDto {

    private UUID paymentOrderId;

    /** The payment's status, e.g. AUTHORIZED, or PLACEMENT_FAILED when the money was received but no order could be made. */
    private String status;

    /** True once the orders exist. */
    private boolean ordersPlaced;

    /** Ids of the orders created for this payment; empty when {@link #ordersPlaced} is false. */
    private List<UUID> orderIds;

    /** Why the orders could not be placed; null otherwise. */
    private String message;
}
