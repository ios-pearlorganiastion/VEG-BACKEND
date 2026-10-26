package com.veggofresh.banner.service;

import com.veggofresh.banner.dto.CustomerBannerDto;
import com.veggofresh.banner.entity.Banner;
import com.veggofresh.banner.entity.BannerActionType;
import com.veggofresh.banner.repository.BannerRepository;
import com.veggofresh.coupon.service.CouponCheckoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerBannerServiceImpl implements CustomerBannerService {

    private final BannerRepository bannerRepository;
    private final CouponCheckoutService couponCheckoutService;

    Instant now() {
        return Instant.now();
    }

    @Override
    public List<CustomerBannerDto> getBanners(UUID userId) {
        List<Banner> candidates = bannerRepository.findInSchedule(now());
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> codes = new HashSet<>();
        Set<UUID> categoryIds = new HashSet<>();
        Set<UUID> productIds = new HashSet<>();
        for (Banner b : candidates) {
            if (b.getCouponCode() != null) {
                codes.add(b.getCouponCode());
            }
            if (b.getActionType() == BannerActionType.CATEGORY && b.getActionTargetId() != null) {
                categoryIds.add(b.getActionTargetId());
            } else if (b.getActionType() == BannerActionType.PRODUCT && b.getActionTargetId() != null) {
                productIds.add(b.getActionTargetId());
            }
        }
        // A banner never shows a coupon this customer cannot use (gone, off, expired, used up, or not for them).
        Set<String> usable = codes.isEmpty() ? Set.of() : couponCheckoutService.usableCodes(userId, codes);
        Set<UUID> liveCategories = categoryIds.isEmpty() ? Set.of() : new HashSet<>(bannerRepository.findActiveCategoryIds(categoryIds));
        Set<UUID> liveProducts = productIds.isEmpty() ? Set.of() : new HashSet<>(bannerRepository.findActiveProductIds(productIds));

        List<CustomerBannerDto> out = new ArrayList<>();
        for (Banner b : candidates) {
            if (b.getCouponCode() != null && !usable.contains(b.getCouponCode())) {
                continue;
            }
            if (b.getActionType() == BannerActionType.CATEGORY && !liveCategories.contains(b.getActionTargetId())) {
                continue;
            }
            if (b.getActionType() == BannerActionType.PRODUCT && !liveProducts.contains(b.getActionTargetId())) {
                continue;
            }
            out.add(CustomerBannerDto.builder()
                    .id(b.getId())
                    .title(b.getTitle())
                    .subtitle(b.getSubtitle())
                    .imageUrl(b.getImageUrl())
                    .couponCode(b.getCouponCode())
                    .actionType(b.getActionType())
                    .actionTargetId(b.getActionTargetId())
                    .build());
        }
        return out;
    }
}
