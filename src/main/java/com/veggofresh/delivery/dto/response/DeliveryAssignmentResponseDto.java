package com.veggofresh.delivery.dto.response;

import com.veggofresh.delivery.entity.DeliveryAssignmentStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class DeliveryAssignmentResponseDto {
    private UUID id;
    private UUID orderId;
    private DeliveryAssignmentStatus status;
    private double pickupLatitude;
    private double pickupLongitude;
    private double dropLatitude;
    private double dropLongitude;

    /**
     * Customer's delivery address as free text -- a dispatch-time snapshot,
     * same idea as shopAddress below. Populated on both the pre-accept
     * (light) and post-accept (full) views, same visibility rule as
     * shopName/shopAddress, since it's needed for navigation before a
     * partner decides whether to accept.
     */
    private String dropAddress;

    private Instant assignedAt;
    private Instant expiresAt;

    // Phase B: contact info + timeline. shopPhone/customerPhone resolved live via
    // UserLookupService; shopName/shopAddress are the dispatch-time snapshot.
    // customerPhone/customerName/items/orderTotal are nullable: only populated
    // on the single-assignment detail view, and only once THIS partner has
    // actually accepted the assignment (see mapToFullDto) -- shown on the
    // request/nearby list, shopName/shopAddress and estimatedEarning are the
    // only identity-adjacent fields exposed pre-accept.
    private String shopName;
    private String shopAddress;
    private String shopPhone;
    private String customerName;
    private String customerPhone;

    /**
     * Up to 3 product image URLs for this order -- same source
     * (CatalogProduct.imageUrl) and shape as OrderResponseDto.itemThumbnails,
     * resolved via CustomerOrderService.getOrderByIdForFulfillment(orderId).
     * Populated on BOTH the pre-accept (light/nearby) and post-accept (full)
     * views, unlike items/customerName/orderTotal below -- product photos
     * aren't customer-identifying, so there's no reason to withhold them
     * before a partner decides whether to accept.
     */
    private List<String> itemThumbnails;

    /**
     * What the partner will earn for this delivery: the order's delivery fee -- the
     * admin-configured flat amount (PlatformSettings.deliveryFeeAmount) fixed on the order
     * at checkout. It is exactly what completeDelivery() credits to their wallet and
     * records as their earning, so this preview always matches the eventual payout.
     * Populated on both the pre-accept (light) and post-accept (full) views,
     * since it's the partner's own potential earning and doesn't depend on
     * anyone else's identity.
     */
    private BigDecimal estimatedEarning;

    /** Order contents -- only populated once this partner has accepted (see mapToFullDto). */
    private List<OrderItemSummaryDto> items;
    private BigDecimal orderTotal;

    private List<TimelineEntryDto> timeline;
    private ProofOfDeliveryResponseDto proofOfDelivery;

    @Getter
    @Builder
    public static class TimelineEntryDto {
        private DeliveryAssignmentStatus status;
        private Instant occurredAt;
    }

    @Getter
    @Builder
    public static class OrderItemSummaryDto {
        private UUID productId;
        private String productName;
        private int quantity;
        private BigDecimal price;
        private BigDecimal subTotal;
    }
}
