package com.veggofresh.vendor.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Figma "Order Details" screen. IMPORTANT: subtotal and estimatedPayout here are scoped to
 * THIS SHOP's items only, not the full order -- a single Customer order could span
 * multiple vendors' products (nothing scopes a cart to one shop), so 'items' is
 * filtered down before these totals are computed.
 */
@Getter
@Builder
public class VendorOrderDetailResponseDto {
    private UUID orderId;
    private String status;
    private List<VendorOrderItemDto> items;

    /**
     * Customer identity -- both null until THIS shop has actually won the
     * order (not merely a pending candidate). See getOrderDetail()'s isAccepted
     * gating.
     */
    private String customerName;
    private String customerPhone;

    private String deliveryAddress;
    private Double latitude;
    private Double longitude;

    /** Sum of this shop's items only. */
    private BigDecimal subtotal;

    /**
     * What this shop will actually be paid: the full product subtotal -- nothing is
     * deducted from the vendor (the platform fee and delivery fee are charged to the
     * customer separately). Matches what PaymentServiceImpl.onDeliveryCompleted
     * credits to the vendor wallet -- this is a preview of that same
     * number, not a separate concept. Visible at request time (before accept)
     * as well as after, since it's this vendor's own money and doesn't involve
     * anyone else's identity.
     */
    private BigDecimal estimatedPayout;

    /**
     * Delivery partner identity -- null until dispatch has happened AND a
     * partner has accepted. Folded in from DeliveryPickupInfoService so the
     * vendor doesn't need a second call once they're already viewing this
     * order's detail.
     */
    private String deliveryPartnerName;
    private String deliveryPartnerPhone;
    private String deliveryStatus;

    private Instant createdAt;
    private Instant updatedAt;
}
