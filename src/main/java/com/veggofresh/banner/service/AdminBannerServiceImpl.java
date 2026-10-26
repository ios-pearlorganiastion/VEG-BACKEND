package com.veggofresh.banner.service;

import com.veggofresh.banner.dto.AdminBannerDto;
import com.veggofresh.banner.dto.BannerRequestDto;
import com.veggofresh.banner.entity.Banner;
import com.veggofresh.banner.entity.BannerActionType;
import com.veggofresh.banner.repository.BannerRepository;
import com.veggofresh.coupon.service.AdminCouponService;
import com.veggofresh.platform.common.IstDateTime;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.platform.storage.CloudinaryService;
import com.veggofresh.platform.storage.CloudinaryUploadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminBannerServiceImpl implements AdminBannerService {

    static final String IMAGE_FOLDER = "veggofresh/banners";
    static final String COUPON_MISSING = "MISSING";

    private final BannerRepository bannerRepository;
    private final CloudinaryService cloudinaryService;
    private final AdminCouponService adminCouponService;

    Instant now() {
        return Instant.now();
    }

    // ------------------------------------------------------------------------------------------------------
    // Create / update
    // ------------------------------------------------------------------------------------------------------

    /** Everything validated and normalised, with no side effects (nothing is uploaded until all of this passes). */
    private record Checked(String title, String subtitle, String couponCode, BannerActionType actionType,
                           UUID actionTargetId, Instant startsAt, Instant endsAt) {
    }

    @Override
    public AdminBannerDto create(BannerRequestDto r) {
        Checked c = check(r, true);
        if (r.getImage() == null || r.getImage().isEmpty()) {
            throw bad("BANNER_IMAGE_REQUIRED", "A banner needs an image");
        }
        if (r.getDisplayOrder() != null && r.getDisplayOrder() < 0) {
            throw bad("BANNER_ORDER_INVALID", "Display order cannot be negative");
        }

        CloudinaryUploadResult upload = cloudinaryService.uploadImage(r.getImage(), IMAGE_FOLDER);
        // If the surrounding transaction fails to commit, the image we just uploaded is removed again.
        afterCompletion(null, upload.publicId());

        Banner b = new Banner();
        apply(b, c);
        b.setImageUrl(upload.url());
        b.setImagePublicId(upload.publicId());
        b.setDisplayOrder(r.getDisplayOrder() != null ? r.getDisplayOrder() : bannerRepository.maxDisplayOrder() + 1);
        b.setEnabled(r.getEnabled() == null || r.getEnabled());
        return one(bannerRepository.save(b));
    }

    @Override
    public AdminBannerDto update(UUID id, BannerRequestDto r) {
        Banner b = load(id);
        Checked c = check(r, false);
        if (r.getDisplayOrder() != null && r.getDisplayOrder() < 0) {
            throw bad("BANNER_ORDER_INVALID", "Display order cannot be negative");
        }

        apply(b, c);
        if (r.getDisplayOrder() != null) {
            b.setDisplayOrder(r.getDisplayOrder());
        }
        if (r.getEnabled() != null) {
            b.setEnabled(r.getEnabled());
        }
        if (r.getImage() != null && !r.getImage().isEmpty()) {
            CloudinaryUploadResult upload = cloudinaryService.uploadImage(r.getImage(), IMAGE_FOLDER);
            String oldPublicId = b.getImagePublicId();
            b.setImageUrl(upload.url());
            b.setImagePublicId(upload.publicId());
            // Committed: drop the old image. Rolled back: drop the new one instead.
            afterCompletion(oldPublicId, upload.publicId());
        }
        return one(bannerRepository.save(b));
    }

    private void apply(Banner b, Checked c) {
        b.setTitle(c.title());
        b.setSubtitle(c.subtitle());
        b.setCouponCode(c.couponCode());
        b.setActionType(c.actionType());
        b.setActionTargetId(c.actionTargetId());
        b.setStartsAt(c.startsAt());
        b.setEndsAt(c.endsAt());
    }

    private Checked check(BannerRequestDto r, boolean creating) {
        String title = r.getTitle() == null ? "" : r.getTitle().trim();
        if (title.isEmpty()) {
            throw bad("BANNER_TITLE_REQUIRED", "Banner title is required");
        }
        if (title.length() > 120) {
            throw bad("BANNER_TITLE_INVALID", "The title can be at most 120 characters");
        }
        String subtitle = r.getSubtitle() == null || r.getSubtitle().isBlank() ? null : r.getSubtitle().trim();
        if (subtitle != null && subtitle.length() > 255) {
            throw bad("BANNER_SUBTITLE_INVALID", "The subtitle can be at most 255 characters");
        }

        // A banner never advertises a code that does not exist.
        String couponCode = r.getCouponCode() == null || r.getCouponCode().isBlank()
                ? null : r.getCouponCode().trim().toUpperCase(Locale.ROOT);
        if (couponCode != null && adminCouponService.statusOfCode(couponCode) == null) {
            throw bad("BANNER_COUPON_NOT_FOUND",
                    "There is no coupon with the code " + couponCode + ". Create the coupon first, or clear the code.");
        }

        BannerActionType type = r.getActionType() == null ? BannerActionType.NONE : r.getActionType();
        UUID target = null;
        if (type != BannerActionType.NONE) {
            target = r.getActionTargetId();
            if (target == null) {
                throw bad("BANNER_TARGET_REQUIRED", "Choose the " + type.name().toLowerCase(Locale.ROOT)
                        + " this banner should open");
            }
            Set<UUID> active = activeIds(type, List.of(target));
            if (!active.contains(target)) {
                throw bad("BANNER_TARGET_NOT_FOUND", "That " + type.name().toLowerCase(Locale.ROOT)
                        + " does not exist or is switched off");
            }
        }

        Instant startsAt = parse(r.getStartsAt(), "startsAt");
        Instant endsAt = parse(r.getEndsAt(), "endsAt");
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw bad("BANNER_DATES_INVALID", "The end date must be after the start date");
        }
        if (creating && endsAt != null && !endsAt.isAfter(now())) {
            throw bad("BANNER_DATES_INVALID", "The end date is already in the past");
        }
        return new Checked(title, subtitle, couponCode, type, target, startsAt, endsAt);
    }

    // ------------------------------------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public AdminBannerDto get(UUID id) {
        return one(load(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminBannerDto> list(String status, String search, int page, int size) {
        String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        String q = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);

        List<Banner> matching = new ArrayList<>();
        for (Banner b : bannerRepository.findAllInOrder()) {
            if (q != null && !(b.getTitle().toLowerCase(Locale.ROOT).contains(q)
                    || (b.getSubtitle() != null && b.getSubtitle().toLowerCase(Locale.ROOT).contains(q)))) {
                continue;
            }
            if (wanted != null && !wanted.equals(statusOf(b))) {
                continue;
            }
            matching.add(b);
        }

        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        int from = (int) Math.min((long) safePage * safeSize, matching.size());
        int to = Math.min(from + safeSize, matching.size());
        List<AdminBannerDto> content = map(matching.subList(from, to));
        return new PageImpl<>(content, PageRequest.of(safePage, safeSize), matching.size());
    }

    // ------------------------------------------------------------------------------------------------------
    // Switch / delete
    // ------------------------------------------------------------------------------------------------------

    @Override
    public AdminBannerDto setEnabled(UUID id, boolean enabled) {
        Banner b = load(id);
        b.setEnabled(enabled);
        return one(bannerRepository.save(b));
    }

    @Override
    public void delete(UUID id) {
        Banner b = load(id);
        b.softDelete();
        bannerRepository.save(b);
        afterCompletion(b.getImagePublicId(), null);
    }

    // ------------------------------------------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------------------------------------------

    private AdminBannerDto one(Banner b) {
        return map(List.of(b)).get(0);
    }

    /** Maps banners, asking each coupon's status and each tap target's availability once for the whole batch. */
    private List<AdminBannerDto> map(List<Banner> banners) {
        Map<String, String> couponStatus = new HashMap<>();
        Set<UUID> categoryIds = new HashSet<>();
        Set<UUID> productIds = new HashSet<>();
        for (Banner b : banners) {
            if (b.getCouponCode() != null && !couponStatus.containsKey(b.getCouponCode())) {
                String s = adminCouponService.statusOfCode(b.getCouponCode());
                couponStatus.put(b.getCouponCode(), s == null ? COUPON_MISSING : s);
            }
            if (b.getActionType() == BannerActionType.CATEGORY && b.getActionTargetId() != null) {
                categoryIds.add(b.getActionTargetId());
            } else if (b.getActionType() == BannerActionType.PRODUCT && b.getActionTargetId() != null) {
                productIds.add(b.getActionTargetId());
            }
        }
        Set<UUID> activeCategories = categoryIds.isEmpty() ? Set.of() : activeIds(BannerActionType.CATEGORY, categoryIds);
        Set<UUID> activeProducts = productIds.isEmpty() ? Set.of() : activeIds(BannerActionType.PRODUCT, productIds);

        List<AdminBannerDto> out = new ArrayList<>(banners.size());
        for (Banner b : banners) {
            String status = statusOf(b);
            String coupon = b.getCouponCode() == null ? null : couponStatus.get(b.getCouponCode());
            Boolean targetOk = null;
            if (b.getActionType() == BannerActionType.CATEGORY) {
                targetOk = activeCategories.contains(b.getActionTargetId());
            } else if (b.getActionType() == BannerActionType.PRODUCT) {
                targetOk = activeProducts.contains(b.getActionTargetId());
            }
            boolean showing = "LIVE".equals(status)
                    && (coupon == null || "LIVE".equals(coupon))
                    && (targetOk == null || targetOk);
            out.add(AdminBannerDto.builder()
                    .id(b.getId())
                    .title(b.getTitle())
                    .subtitle(b.getSubtitle())
                    .imageUrl(b.getImageUrl())
                    .couponCode(b.getCouponCode())
                    .actionType(b.getActionType())
                    .actionTargetId(b.getActionTargetId())
                    .startsAt(b.getStartsAt())
                    .endsAt(b.getEndsAt())
                    .startsAtIst(IstDateTime.format(b.getStartsAt()))
                    .endsAtIst(IstDateTime.format(b.getEndsAt()))
                    .displayOrder(b.getDisplayOrder())
                    .enabled(b.isEnabled())
                    .status(status)
                    .couponStatus(coupon)
                    .targetAvailable(targetOk)
                    .showing(showing)
                    .createdAt(b.getCreatedAt())
                    .updatedAt(b.getUpdatedAt())
                    .build());
        }
        return out;
    }

    /** DISABLED, then EXPIRED, then SCHEDULED, else LIVE. */
    String statusOf(Banner b) {
        Instant now = now();
        if (!b.isEnabled()) {
            return "DISABLED";
        }
        if (b.getEndsAt() != null && !now.isBefore(b.getEndsAt())) {
            return "EXPIRED";
        }
        if (b.getStartsAt() != null && now.isBefore(b.getStartsAt())) {
            return "SCHEDULED";
        }
        return "LIVE";
    }

    // ------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------

    private Set<UUID> activeIds(BannerActionType type, java.util.Collection<UUID> ids) {
        List<UUID> found = type == BannerActionType.CATEGORY
                ? bannerRepository.findActiveCategoryIds(ids)
                : bannerRepository.findActiveProductIds(ids);
        return new HashSet<>(found);
    }

    private Banner load(UUID id) {
        return bannerRepository.findById(id)
                .orElseThrow(() -> new BusinessException("BANNER_NOT_FOUND", "Banner not found", HttpStatus.NOT_FOUND));
    }

    private static BusinessException bad(String code, String message) {
        return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }

    private static Instant parse(String text, String field) {
        try {
            return IstDateTime.parse(text).orElse(null);
        } catch (DateTimeParseException e) {
            throw bad("BANNER_DATES_INVALID",
                    field + " must be an ISO date-time such as 2026-11-01T00:00:00 (Indian time)");
        }
    }

    /**
     * Cloudinary is not transactional, so image clean-up waits for the database outcome: {@code ifCommitted} is deleted
     * when the transaction commits (an image that is no longer used), {@code ifRolledBack} when it does not (an image
     * that was just uploaded but never attached). Outside a transaction the change is already final.
     */
    private void afterCompletion(String ifCommitted, String ifRolledBack) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cloudinaryService.deleteQuietly(ifCommitted);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                cloudinaryService.deleteQuietly(status == STATUS_COMMITTED ? ifCommitted : ifRolledBack);
            }
        });
    }
}
