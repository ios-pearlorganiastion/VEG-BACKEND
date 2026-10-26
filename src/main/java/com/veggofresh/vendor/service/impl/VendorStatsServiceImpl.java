package com.veggofresh.vendor.service.impl;

import com.veggofresh.customer.dto.response.OrderResponseDto;
import com.veggofresh.customer.service.CustomerOrderService;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.dto.response.VendorProfileStatsResponseDto;
import com.veggofresh.vendor.entity.KycStatus;
import com.veggofresh.vendor.entity.Shop;
import com.veggofresh.vendor.entity.VendorShopRating;
import com.veggofresh.vendor.repository.ShopRepository;
import com.veggofresh.vendor.repository.VendorListingRepository;
import com.veggofresh.vendor.repository.VendorShopRatingRepository;
import com.veggofresh.vendor.service.VendorStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * FIXED THIS ROUND: totalSales now computed via
 * CustomerOrderService.getAcceptedOrdersForShop(...) -- previously used the now-removed
 * getOrdersByShopId(...), which (before the vendor-broadcast redesign) matched every
 * order this shop was ever a CANDIDATE for, not just orders it actually won. Under the
 * old single-vendor-per-product model that distinction didn't exist; it does now.
 * Deliberately NOT importing Customer's Order entity directly (unlike the
 * still-unfixed VendorDashboardService/VendorReportService), to avoid adding a third
 * instance of that same boundary violation.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VendorStatsServiceImpl implements VendorStatsService {

    private final ShopRepository shopRepository;
    private final VendorListingRepository vendorListingRepository;
    private final CustomerOrderService customerOrderService;
    private final VendorShopRatingRepository ratingRepository;

    @Override
    public VendorProfileStatsResponseDto getProfileStats(UUID ownerUserId) {
        Shop shop = shopRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId)
                .orElseThrow(() -> new BusinessException("VENDOR_SHOP_NOT_FOUND", "Shop not found", HttpStatus.NOT_FOUND));

        BigDecimal totalSales = customerOrderService.getAcceptedOrdersForShop(shop.getId()).stream()
                .filter(order -> "DELIVERED".equals(order.getStatus()))
                .flatMap(order -> order.getItems().stream())
                .filter(item -> belongsToShop(item.getProductId(), shop.getId()))
                .map(item -> item.vendorSubTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long activeItemsCount = vendorListingRepository.countByShopIdAndIsListedTrue(shop.getId());

        List<VendorShopRating> ratings = ratingRepository.findByShopId(shop.getId());
        Double storeRating = ratings.isEmpty() ? null
                : ratings.stream().mapToInt(VendorShopRating::getRatingValue).average().orElse(0.0);

        return VendorProfileStatsResponseDto.builder()
                .totalSales(totalSales)
                .storeRating(storeRating != null ? Math.round(storeRating * 10.0) / 10.0 : null)
                .ratingCount(ratings.size())
                .activeItemsCount(activeItemsCount)
                .isVerified(shop.getKycStatus() == KycStatus.APPROVED)
                .build();
    }

    private boolean belongsToShop(UUID catalogProductId, UUID shopId) {
        return vendorListingRepository.findByShopIdAndCatalogProductId(shopId, catalogProductId).isPresent();
    }
}
