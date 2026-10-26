package com.veggofresh.vendor.service;

import com.veggofresh.admin.dto.response.ProductResponseDto;
import com.veggofresh.admin.service.AdminProductService;
import com.veggofresh.customer.dto.response.OrderResponseDto;
import com.veggofresh.customer.entity.OrderStatus;
import com.veggofresh.customer.service.CustomerOrderService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.dto.response.DashboardSummaryResponseDto;
import com.veggofresh.vendor.dto.response.RecentOrderDto;
import com.veggofresh.vendor.entity.Shop;
import com.veggofresh.vendor.repository.ShopRepository;
import com.veggofresh.vendor.repository.VendorListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FIXED this round: was directly injecting Customer's OrderRepository/Order
 * entity, bypassing CustomerOrderService -- same violation VendorReportService
 * had, fixed the same way and for the same reason (OrderRepository.findByShopId()
 * was removed as part of the accept/reject broadcast redesign). Now goes through
 * CustomerOrderService.getAcceptedOrdersForShop(), which also more correctly
 * reflects orders this shop actually won rather than every broadcast candidate.
 */
@Service
@RequiredArgsConstructor
public class VendorDashboardService {

    private final ShopRepository shopRepository;
    private final VendorListingRepository vendorListingRepository;
    private final AdminProductService adminProductService;
    private final CustomerOrderService customerOrderService;

    @Transactional(readOnly = true)
    public DashboardSummaryResponseDto getDashboardSummary(UUID ownerUserId) {
        Shop shop = shopRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId)
                .orElseThrow(() -> new BusinessException("SHOP_NOT_FOUND", "Vendor shop not found"));

        // NEW ARCHITECTURE: stock is no longer tracked at all (vendor accepts/
        // rejects each order manually based on real stock at the time) -- this
        // card is vestigial, kept at 0 rather than removed from the DTO/API
        // contract outright. See NOTES_VENDOR.md.
        long outOfStockCount = 0;

        List<OrderResponseDto> allShopOrders = customerOrderService.getAcceptedOrdersForShop(shop.getId());

        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);

        BigDecimal todaysRevenue = allShopOrders.stream()
                .filter(order -> order.getCreatedAt().isAfter(startOfToday))
                .map(order -> calculateShopRevenueForOrder(order, shop.getId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Instant startOfYesterday = startOfToday.minus(1, ChronoUnit.DAYS);
        BigDecimal yesterdaysRevenue = allShopOrders.stream()
                .filter(order -> order.getCreatedAt().isAfter(startOfYesterday) && order.getCreatedAt().isBefore(startOfToday))
                .map(order -> calculateShopRevenueForOrder(order, shop.getId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double changePercent = 0.0;
        if (yesterdaysRevenue.compareTo(BigDecimal.ZERO) > 0) {
            changePercent = todaysRevenue.subtract(yesterdaysRevenue)
                    .divide(yesterdaysRevenue, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        }

        long activeOrdersCount = allShopOrders.stream()
                .filter(order -> !OrderStatus.DELIVERED.name().equals(order.getStatus()) && !OrderStatus.CANCELLED.name().equals(order.getStatus()))
                .count();

        long pendingPickupCount = allShopOrders.stream()
                .filter(order -> OrderStatus.CONFIRMED.name().equals(order.getStatus()) || OrderStatus.PREPARING.name().equals(order.getStatus()))
                .count();

        List<Integer> performanceTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            Instant dayStart = startOfToday.minus(i, ChronoUnit.DAYS);
            Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
            BigDecimal dayRevenue = allShopOrders.stream()
                    .filter(order -> order.getCreatedAt().isAfter(dayStart) && order.getCreatedAt().isBefore(dayEnd))
                    .map(order -> calculateShopRevenueForOrder(order, shop.getId()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            performanceTrend.add(dayRevenue.intValue());
        }

        List<RecentOrderDto> recentOrders = allShopOrders.stream()
                .sorted((o1, o2) -> o2.getCreatedAt().compareTo(o1.getCreatedAt()))
                .limit(5)
                .map(order -> mapToRecentOrderDto(order, shop.getId()))
                .collect(Collectors.toList());

        return DashboardSummaryResponseDto.builder()
                .businessName(shop.getName())
                .todaysRevenue(todaysRevenue.doubleValue())
                .revenueChangePercent(changePercent)
                .activeOrdersCount((int) activeOrdersCount)
                .pendingPickupCount((int) pendingPickupCount)
                .outOfStockCount((int) outOfStockCount)
                .performanceTrend(performanceTrend)
                .recentOrders(recentOrders)
                .build();
    }

    private BigDecimal calculateShopRevenueForOrder(OrderResponseDto order, UUID shopId) {
        return order.getItems().stream()
                .map(item -> {
                    if (vendorListingRepository.findByShopIdAndCatalogProductId(shopId, item.getProductId()).isPresent()) {
                        return item.vendorSubTotal();
                    }
                    return BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private RecentOrderDto mapToRecentOrderDto(OrderResponseDto order, UUID shopId) {
        String itemsSummary = order.getItems().stream()
                .map(item -> {
                    String name = resolveProductName(item.getProductId());
                    return name + " x " + item.getQuantity();
                })
                .collect(Collectors.joining(", "));

        BigDecimal shopAmount = calculateShopRevenueForOrder(order, shopId);

        return RecentOrderDto.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .itemsSummary(itemsSummary)
                .timeAgo(formatTimeAgo(order.getCreatedAt()))
                .amount(shopAmount.doubleValue())
                .status(order.getStatus().toLowerCase())
                .itemThumbnails(order.getItemThumbnails())
                .build();
    }

    private String resolveProductName(UUID catalogProductId) {
        // Optional lookup, not getProductById-in-a-try/catch: AdminProductServiceImpl
        // is @Transactional and joins the caller's transaction, so a thrown
        // exception would mark it rollback-only and resurface as
        // UnexpectedRollbackException at commit.
        return adminProductService.findProductById(catalogProductId)
                .map(ProductResponseDto::getName)
                .orElse("Unknown Product");
    }

    private String formatTimeAgo(Instant createdAt) {
        long minutes = ChronoUnit.MINUTES.between(createdAt, Instant.now());
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + " mins ago";
        long hours = ChronoUnit.HOURS.between(createdAt, Instant.now());
        if (hours < 24) return hours + " hours ago";
        long days = ChronoUnit.DAYS.between(createdAt, Instant.now());
        return days + " days ago";
    }
}
