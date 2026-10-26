package com.veggofresh.vendor.service;

import com.veggofresh.admin.dto.response.ProductResponseDto;
import com.veggofresh.admin.service.AdminProductService;
import com.veggofresh.customer.dto.response.OrderResponseDto;
import com.veggofresh.customer.entity.OrderStatus;
import com.veggofresh.customer.service.CustomerOrderService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.entity.Shop;
import com.veggofresh.vendor.repository.ShopRepository;
import com.veggofresh.vendor.repository.VendorListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FIXED this round: was directly injecting Customer's OrderRepository/Order
 * entity, bypassing CustomerOrderService entirely -- a boundary violation the
 * file's own previous comment already flagged as deliberately deferred. Fixed
 * now (rather than left deferred) because OrderRepository.findByShopId() was
 * removed as part of the accept/reject broadcast redesign, turning the
 * deferred debt into an active compile error. Now goes through
 * CustomerOrderService.getAcceptedOrdersForShop(), which is also more
 * correct: it returns only orders this shop actually won, not every order it
 * was ever a broadcast candidate for.
 */
@Service
@RequiredArgsConstructor
public class VendorReportService {

    private final ShopRepository shopRepository;
    private final CustomerOrderService customerOrderService;
    private final VendorListingRepository vendorListingRepository;
    private final AdminProductService adminProductService;

    @Transactional(readOnly = true)
    public Map<String, Object> getSalesReports(UUID ownerUserId) {
        Shop shop = shopRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId)
                .orElseThrow(() -> new BusinessException("VENDOR_SHOP_NOT_FOUND", "Shop not found"));

        List<OrderResponseDto> orders = customerOrderService.getAcceptedOrdersForShop(shop.getId());

        long totalSalesCount = orders.stream()
                .filter(o -> OrderStatus.DELIVERED.name().equals(o.getStatus()))
                .count();

        BigDecimal totalRevenue = orders.stream()
                .filter(o -> OrderStatus.DELIVERED.name().equals(o.getStatus()))
                .map(o -> calculateShopRevenueForOrder(o, shop.getId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Integer> salesByProduct = new HashMap<>();
        orders.stream()
                .filter(o -> OrderStatus.DELIVERED.name().equals(o.getStatus()))
                .flatMap(o -> o.getItems().stream())
                .forEach(item -> {
                    if (vendorListingRepository.findByShopIdAndCatalogProductId(shop.getId(), item.getProductId()).isPresent()) {
                        String name = resolveProductName(item.getProductId());
                        salesByProduct.merge(name, item.getQuantity(), Integer::sum);
                    }
                });

        return Map.of(
                "shopId", shop.getId(),
                "totalSalesCount", totalSalesCount,
                "totalRevenue", totalRevenue.doubleValue(),
                "salesByProduct", salesByProduct
        );
    }
    

    @Transactional(readOnly = true)
    public Map<String, Object> getEarningsReports(UUID ownerUserId) {
        Shop shop = shopRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId)
                .orElseThrow(() -> new BusinessException("VENDOR_SHOP_NOT_FOUND", "Shop not found"));

        List<OrderResponseDto> orders = customerOrderService.getAcceptedOrdersForShop(shop.getId());

        BigDecimal totalEarnings = orders.stream()
                .filter(o -> OrderStatus.DELIVERED.name().equals(o.getStatus()))
                .map(o -> calculateShopRevenueForOrder(o, shop.getId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pendingEarnings = orders.stream()
                .filter(o -> !OrderStatus.DELIVERED.name().equals(o.getStatus()) && !OrderStatus.CANCELLED.name().equals(o.getStatus()))
                .map(o -> calculateShopRevenueForOrder(o, shop.getId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return Map.of(
                "shopId", shop.getId(),
                "totalEarnings", totalEarnings.doubleValue(),
                "pendingEarnings", pendingEarnings.doubleValue(),
                "currency", "INR"
        );
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

    private String resolveProductName(UUID catalogProductId) {
        // Optional lookup, not getProductById-in-a-try/catch: AdminProductServiceImpl
        // is @Transactional and joins the caller's transaction, so a thrown
        // exception would mark it rollback-only and resurface as
        // UnexpectedRollbackException at commit.
        return adminProductService.findProductById(catalogProductId)
                .map(ProductResponseDto::getName)
                .orElse("Unknown Product");
    }
}
