package com.veggofresh.vendor.service;

import com.veggofresh.admin.service.PlatformSettingsService;
import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.customer.dto.response.OrderItemResponseDto;
import com.veggofresh.customer.dto.response.OrderResponseDto;
import com.veggofresh.customer.service.CustomerOrderService;
import com.veggofresh.delivery.dto.VendorDeliveryStatusDto;
import com.veggofresh.delivery.service.DeliveryDispatchService;
import com.veggofresh.delivery.service.DeliveryPickupInfoService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.dto.response.VendorOrderDetailResponseDto;
import com.veggofresh.vendor.dto.response.VendorOrderItemDto;
import com.veggofresh.vendor.entity.Shop;
import com.veggofresh.vendor.repository.ShopRepository;
import com.veggofresh.vendor.repository.VendorListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REBUILT THIS ROUND -- the vendor-accept/reject broadcast redesign, matching
 * CustomerOrderService's rebuild. Two genuinely different lists now exist:
 * getOrderRequests (the broadcast inbox -- still-live candidates) and
 * getShopOrders
 * (real order history -- only orders THIS shop actually won). Confirmed via
 * live
 * testing that the old single-list model let a vendor who lost the accept race
 * keep
 * seeing and acting on an order they never won -- see NOTES_VENDOR.md for the
 * full
 * root-cause trace.
 */
@Service
@RequiredArgsConstructor
public class VendorOrderManagementService {

    private final CustomerOrderService customerOrderService;
    private final ShopRepository shopRepository;
    private final VendorListingRepository vendorListingRepository;
    private final UserLookupService userLookupService;
    private final DeliveryDispatchService deliveryDispatchService;
    private final DeliveryPickupInfoService deliveryPickupInfoService;
    private final PlatformSettingsService platformSettingsService;

    /**
     * NEW THIS ROUND -- the broadcast inbox. Every order still awaiting a decision
     * that this shop can still act on (candidate, not yet accepted by anyone,
     * hasn't
     * rejected it themselves). This is where accept()/reject() are meant to be
     * called
     * from.
     */
    @Transactional(readOnly = true)
    public List<OrderResponseDto> getOrderRequests(UUID ownerUserId) {
        Shop shop = requireShop(ownerUserId);
        List<OrderResponseDto> requests = customerOrderService.getOrderRequestsForShop(shop.getId());
        requests.forEach(order -> {
            applyEstimatedPayout(order);
            // The shared OrderResponseMapper always resolves customerName (it's
            // harmless on the customer's own view and needed once accepted) --
            // but THIS list is still-live candidates, not yet won by this shop.
            // Per the agreed rule, customer identity must stay hidden until
            // acceptance, so it's explicitly stripped here even though the DTO
            // technically carries it.
            order.setCustomerName(null);
        });
        return requests;
    }

    /**
     * CHANGED MEANING THIS ROUND -- this is now real order history only: orders
     * this
     * shop actually WON the accept race for. Previously returned every order this
     * shop was ever a candidate for, which is the exact bug this round fixes -- a
     * vendor who lost the race no longer sees the order here at all.
     */
    @Transactional(readOnly = true)
    public List<OrderResponseDto> getShopOrders(UUID ownerUserId) {
        Shop shop = requireShop(ownerUserId);
        List<OrderResponseDto> orders = customerOrderService.getAcceptedOrdersForShop(shop.getId());
        orders.forEach(this::applyEstimatedPayout);
        return orders;
    }

    /**
     * Enriched single-order view for the Figma "Order Details" screen. Works for
     * both
     * a pending request (viewing detail before deciding) and an already-accepted
     * order
     * (management view) -- accepted-list is checked first, falling back to the
     * pending-requests list. Items are FILTERED to only this
     * shop's own products -- an order can span multiple vendors, so
     * subtotal/fee/total
     * below are scoped accordingly, not the full order's totalAmount. customerPhone
     * resolved live via UserLookupService (no phone denormalization needed --
     * always
     * fresh).
     */
    @Transactional(readOnly = true)
    public VendorOrderDetailResponseDto getOrderDetail(UUID ownerUserId, UUID orderId) {
        Shop shop = requireShop(ownerUserId);

        // Check the accepted list first, then the pending-requests list -- this
        // also tells us, definitively, whether this shop has actually WON the
        // order (not merely a candidate). That distinction gates customer
        // identity and delivery-partner info below -- see class javadoc on why
        // candidacy alone must never be treated as acceptance.
        boolean isAccepted = true;
        OrderResponseDto order = customerOrderService.getAcceptedOrdersForShop(shop.getId()).stream()
                .filter(o -> o.getId().equals(orderId))
                .findFirst()
                .orElse(null);
        if (order == null) {
            isAccepted = false;
            order = customerOrderService.getOrderRequestsForShop(shop.getId()).stream()
                    .filter(o -> o.getId().equals(orderId))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("VENDOR_ORDER_NOT_FOUND", "Order not found for this shop",
                            HttpStatus.NOT_FOUND));
        }

        List<VendorOrderItemDto> shopItems = order.getItems().stream()
                .filter(item -> belongsToShop(item, shop.getId()))
                .map(this::mapItemToDto)
                .collect(Collectors.toList());

        BigDecimal subtotal = shopItems.stream()
                .map(VendorOrderItemDto::getSubTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        VendorOrderDetailResponseDto.VendorOrderDetailResponseDtoBuilder builder = VendorOrderDetailResponseDto
                .builder()
                .orderId(order.getId())
                .status(order.getStatus())
                .items(shopItems)
                .deliveryAddress(order.getDeliveryAddress())
                .latitude(order.getLatitude())
                .longitude(order.getLongitude())
                .subtotal(subtotal)
                .estimatedPayout(estimatedPayout(subtotal))
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt());

        // Customer identity -- gated: a candidate who hasn't won the order yet
        // gets no customer name/phone. This is the exact gap flagged earlier --
        // getOrderDetail() used to resolve customerPhone unconditionally,
        // regardless of acceptance.
        if (isAccepted) {
            builder.customerName(order.getCustomerName());
            builder.customerPhone(userLookupService.findById(order.getUserId())
                    .map(UserSummaryDto::getPhone)
                    .orElse(null));

            // Delivery partner identity -- folded in from Delivery's own status
            // lookup so the vendor's single detail call already has it once
            // dispatched+accepted (VendorDeliveryStatusDto.partnerName/Phone are
            // themselves null until a partner has actually accepted).
            VendorDeliveryStatusDto deliveryStatus = deliveryPickupInfoService.getDeliveryStatusForVendor(orderId,
                    ownerUserId);
            if (deliveryStatus.isDispatched()) {
                builder.deliveryStatus(deliveryStatus.getStatus());
                builder.deliveryPartnerName(deliveryStatus.getPartnerName());
                builder.deliveryPartnerPhone(deliveryStatus.getPartnerPhone());
            }
        }

        return builder.build();
    }

    /**
     * Real settlement formula (matches PaymentServiceImpl.onDeliveryCompleted)
     * computed early as a preview.
     * Vendor receives full product subtotal (the customer pays the admin-configured platform fee and
     * delivery fee separately; nothing is deducted from the vendor).
     */
    private BigDecimal estimatedPayout(BigDecimal productSubtotal) {
        return productSubtotal != null ? productSubtotal : BigDecimal.ZERO;
    }

    /**
     * Applies estimatedPayout (the shop's full product subtotal) onto a
     * shared OrderResponseDto in place.
     */
    private void applyEstimatedPayout(OrderResponseDto order) {
        // What the vendor earns = admin-set vendor price x quantity (not what the customer pays).
        BigDecimal payout = order.getItems().stream()
                .map(OrderItemResponseDto::vendorSubTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setEstimatedPayout(estimatedPayout(payout));

        // The vendor must see ONLY vendor prices. Showing the customer price next to it (or the customer's
        // total / coupon) would reveal the platform margin, so those are replaced for the vendor view.
        // (This DTO instance is built fresh for this request, so changing it here affects nobody else.)
        for (OrderItemResponseDto item : order.getItems()) {
            BigDecimal unit = item.effectiveVendorUnitPrice();
            BigDecimal sub = item.vendorSubTotal();
            item.setPrice(unit);
            item.setSubTotal(sub);
        }
        order.setTotalAmount(payout);
        order.setPromoCode(null);
        order.setPromoDiscount(BigDecimal.ZERO);
    }

    /**
     * BREAKING CHANGE THIS ROUND: acceptOrder now passes shop.getId() through --
     * CustomerOrderService.acceptOrder requires it to record who won the race.
     */
    @Transactional
    public void acceptOrder(UUID ownerUserId, UUID orderId) {
        Shop shop = requireShop(ownerUserId);
        customerOrderService.acceptOrder(orderId, shop.getId());
    }

    /**
     * BREAKING CHANGE THIS ROUND: rejectOrder now passes shop.getId() through, and
     * no
     * longer cancels the whole order -- CustomerOrderService.rejectOrder narrows
     * the
     * candidate pool instead (see that method's own javadoc). This shop stops
     * seeing
     * the order in getOrderRequests either way; the order itself may stay live for
     * other candidates.
     */
    @Transactional
    public void rejectOrder(UUID ownerUserId, UUID orderId) {
        Shop shop = requireShop(ownerUserId);
        customerOrderService.rejectOrder(orderId, shop.getId());
    }

    /**
     * Only valid on orders this shop actually won -- see
     * requireAcceptedShopOrder().
     */
    @Transactional
    public void updateOrderStatus(UUID ownerUserId, UUID orderId, String status) {
        Shop shop = requireShop(ownerUserId);
        requireAcceptedShopOrder(shop, orderId);
        customerOrderService.updateOrderStatus(orderId, status);
    }

    /**
     * NEW THIS ROUND -- the real dispatch trigger. Previously nothing in Vendor
     * called
     * DeliveryDispatchService at all (only DeliveryTestController's /test/dispatch
     * exercised it). This is the vendor physically finishing prep and handing off
     * to
     * delivery: flips the order to READY_FOR_PICKUP, then dispatches to Delivery,
     * which broadcasts to eligible partners within Admin's configured radius of
     * THIS
     * shop's location and issues the pickup OTP once someone accepts (see
     * Delivery's
     * NOTES_DELIVERY.md). Scoped to accepted orders only --
     * requireAcceptedShopOrder
     * throws if this shop never actually won the order (was only ever a candidate,
     * or
     * lost the accept race).
     */
    @Transactional
    public String markReadyForPickup(UUID ownerUserId, UUID orderId) {
        Shop shop = requireShop(ownerUserId);
        OrderResponseDto order = requireAcceptedShopOrder(shop, orderId);

        if (shop.getLatitude() == null || shop.getLongitude() == null) {
            throw new BusinessException("VENDOR_SHOP_LOCATION_MISSING",
                    "Your shop's location isn't set -- update your shop profile before marking orders ready for pickup",
                    HttpStatus.BAD_REQUEST);
        }

        customerOrderService.updateOrderStatus(orderId, "READY_FOR_PICKUP");

        deliveryDispatchService.dispatchOrder(orderId, order.getUserId(), ownerUserId, shop.getName(),
                shop.getAddress(),
                shop.getLatitude(), shop.getLongitude(), order.getLatitude(), order.getLongitude(),
                order.getDeliveryAddress());

        return "Order marked ready for pickup -- nearby delivery partners are being notified";
    }

    /**
     * Shows the vendor the pickup OTP they need to hand over once a delivery
     * partner
     * has accepted. Returns null (not an error) if nobody's accepted yet.
     */
    @Transactional(readOnly = true)
    public String getPickupOtp(UUID ownerUserId, UUID orderId) {
        Shop shop = requireShop(ownerUserId);
        requireAcceptedShopOrder(shop, orderId);
        return deliveryPickupInfoService.getPickupOtpForVendor(orderId, ownerUserId);
    }

    /** "Where's my delivery" for the vendor's own order-detail screen. */
    @Transactional(readOnly = true)
    public VendorDeliveryStatusDto getDeliveryStatus(UUID ownerUserId, UUID orderId) {
        Shop shop = requireShop(ownerUserId);
        requireAcceptedShopOrder(shop, orderId);
        return deliveryPickupInfoService.getDeliveryStatusForVendor(orderId, ownerUserId);
    }

    private Shop requireShop(UUID ownerUserId) {
        return shopRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId)
                .orElseThrow(
                        () -> new BusinessException("VENDOR_SHOP_NOT_FOUND", "Shop not found", HttpStatus.NOT_FOUND));
    }

    /**
     * NEW THIS ROUND -- strict version: only orders this shop actually WON. This is
     * the check that fixes the root-cause bug -- a vendor who was merely a
     * candidate
     * (or who lost the accept race to someone else) gets VENDOR_ORDER_NOT_FOUND
     * here,
     * not a silently-successful action on an order that was never theirs.
     */
    private OrderResponseDto requireAcceptedShopOrder(Shop shop, UUID orderId) {
        return customerOrderService.getAcceptedOrdersForShop(shop.getId()).stream()
                .filter(o -> o.getId().equals(orderId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("VENDOR_ORDER_NOT_FOUND", "Order not found for this shop",
                        HttpStatus.NOT_FOUND));
    }

    // NEW ARCHITECTURE: item.getProductId() is now a catalog product id --
    // "belongs to shop" means this shop currently has (or had) it listed,
    // not that the shop owns a legacy Product row.
    private boolean belongsToShop(OrderItemResponseDto item, UUID shopId) {
        return vendorListingRepository.findByShopIdAndCatalogProductId(shopId, item.getProductId()).isPresent();
    }

    private VendorOrderItemDto mapItemToDto(OrderItemResponseDto item) {
        return VendorOrderItemDto.builder()
                .productId(item.getProductId())
                .productName(item.getProductName())
                .quantity(item.getQuantity())
                .price(item.effectiveVendorUnitPrice())
                .subTotal(item.vendorSubTotal())
                .build();
    }
}
