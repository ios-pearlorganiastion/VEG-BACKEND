package com.veggofresh.coupon.service;

import com.veggofresh.coupon.dto.AdminCouponDto;
import com.veggofresh.coupon.dto.CouponRedemptionRowDto;
import com.veggofresh.coupon.dto.CouponRequestDto;
import com.veggofresh.coupon.entity.Coupon;
import com.veggofresh.coupon.entity.CouponAudience;
import com.veggofresh.coupon.entity.CouponType;
import com.veggofresh.coupon.repository.CouponRedemptionRepository;
import com.veggofresh.coupon.repository.CouponRepository;
import com.veggofresh.coupon.repository.CustomerCouponSelectionRepository;
import com.veggofresh.platform.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminCouponServiceImpl implements AdminCouponService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9_-]{3,40}$");
    private static final DateTimeFormatter IST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final CustomerCouponSelectionRepository selectionRepository;

    Instant now() {
        return Instant.now();
    }

    // ------------------------------------------------------------------------------------------------------
    // Create / update
    // ------------------------------------------------------------------------------------------------------

    @Override
    public AdminCouponDto create(CouponRequestDto request) {
        String code = CouponCheckoutServiceImpl.normalize(request.getCode());
        if (code == null) {
            throw bad("COUPON_CODE_REQUIRED", "Coupon code is required");
        }
        validateCode(code);
        if (couponRepository.countByCode(code) > 0) {
            throw new BusinessException("COUPON_CODE_EXISTS", "A coupon with the code " + code + " already exists",
                    HttpStatus.CONFLICT);
        }

        Coupon c = new Coupon();
        c.setCode(code);
        c.setListed(request.getListed() == null || request.getListed());
        c.setEnabled(request.getEnabled() == null || request.getEnabled());
        applyRules(c, request, true);
        return toDto(couponRepository.save(c), null, null, null);
    }

    @Override
    public AdminCouponDto update(UUID id, CouponRequestDto request) {
        Coupon c = load(id);
        boolean used = redemptionRepository.countAllForCoupon(id) > 0;

        String code = CouponCheckoutServiceImpl.normalize(request.getCode());
        if (code != null && !code.equals(c.getCode())) {
            if (used) {
                throw bad("COUPON_ALREADY_USED", "The code of a coupon that has been used cannot be changed. "
                        + "Create a new coupon instead.");
            }
            validateCode(code);
            if (couponRepository.countByCode(code) > 0) {
                throw new BusinessException("COUPON_CODE_EXISTS", "A coupon with the code " + code + " already exists",
                        HttpStatus.CONFLICT);
            }
            c.setCode(code);
        }
        if (used && request.getDiscountType() != null && request.getDiscountType() != c.getDiscountType()) {
            throw bad("COUPON_ALREADY_USED", "The type of a coupon that has been used cannot be changed. "
                    + "Create a new coupon instead.");
        }
        if (request.getListed() != null) {
            c.setListed(request.getListed());
        }
        if (request.getEnabled() != null) {
            c.setEnabled(request.getEnabled());
        }
        applyRules(c, request, false);
        return toDto(couponRepository.save(c), null, null, null);
    }

    /** Validates the request as a whole and copies the rules onto {@code c}. */
    private void applyRules(Coupon c, CouponRequestDto r, boolean creating) {
        CouponType type = r.getDiscountType();
        if (type == null) {
            throw bad("COUPON_TYPE_REQUIRED", "Discount type is required (PERCENT or FLAT)");
        }
        BigDecimal value = r.getDiscountValue();
        if (value == null || value.signum() <= 0) {
            throw bad("COUPON_VALUE_INVALID", "Discount value must be greater than 0");
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
        BigDecimal maxDiscount = r.getMaxDiscount() == null ? null : r.getMaxDiscount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal minOrder = r.getMinOrderAmount() == null ? BigDecimal.ZERO.setScale(2)
                : r.getMinOrderAmount().setScale(2, RoundingMode.HALF_UP);
        if (minOrder.signum() < 0) {
            throw bad("COUPON_MIN_ORDER_INVALID", "Minimum order amount cannot be negative");
        }

        if (type == CouponType.PERCENT) {
            if (value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw bad("COUPON_VALUE_INVALID", "A percentage must be between 1 and 100");
            }
            if (maxDiscount == null || maxDiscount.signum() <= 0) {
                throw bad("COUPON_MAX_DISCOUNT_REQUIRED",
                        "A percentage coupon needs a maximum discount, so one big basket cannot give away a huge amount");
            }
        } else {
            maxDiscount = null; // a flat coupon is its own cap
            if (minOrder.signum() > 0 && value.compareTo(minOrder) > 0) {
                throw bad("COUPON_VALUE_INVALID",
                        "A flat discount cannot be larger than the minimum order amount");
            }
        }

        Instant startsAt = parseDateTime(r.getStartsAt(), "startsAt");
        Instant endsAt = parseDateTime(r.getEndsAt(), "endsAt");
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw bad("COUPON_DATES_INVALID", "The end date must be after the start date");
        }
        if (creating && endsAt != null && !endsAt.isAfter(now())) {
            throw bad("COUPON_DATES_INVALID", "The end date is already in the past");
        }

        CouponAudience audience = r.getAudience() == null ? CouponAudience.EVERYONE : r.getAudience();
        int minPrev = 0;
        if (audience == CouponAudience.RETURNING_CUSTOMERS) {
            minPrev = r.getMinPreviousOrders() == null ? 1 : r.getMinPreviousOrders();
            if (minPrev < 1) {
                throw bad("COUPON_AUDIENCE_INVALID", "Returning customers need at least 1 earlier order");
            }
        }

        if (r.getTotalUsageLimit() != null && r.getTotalUsageLimit() < 1) {
            throw bad("COUPON_LIMIT_INVALID", "Total uses must be at least 1");
        }
        BigDecimal budget = r.getTotalBudget() == null ? null : r.getTotalBudget().setScale(2, RoundingMode.HALF_UP);
        if (budget != null && budget.signum() <= 0) {
            throw bad("COUPON_LIMIT_INVALID", "Total budget must be greater than 0");
        }
        if ((r.getPerCustomerLimit() == null) != (r.getPerCustomerPeriod() == null)) {
            throw bad("COUPON_LIMIT_INVALID",
                    "Set both the per-customer limit and its period (DAY, WEEK, MONTH or LIFETIME), or neither");
        }
        if (r.getPerCustomerLimit() != null && r.getPerCustomerLimit() < 1) {
            throw bad("COUPON_LIMIT_INVALID", "The per-customer limit must be at least 1");
        }
        String description = r.getDescription() == null || r.getDescription().isBlank() ? null : r.getDescription().trim();
        if (description != null && description.length() > 255) {
            throw bad("COUPON_DESCRIPTION_INVALID", "The description can be at most 255 characters");
        }

        c.setDescription(description);
        c.setDiscountType(type);
        c.setDiscountValue(value);
        c.setMaxDiscount(maxDiscount);
        c.setMinOrderAmount(minOrder);
        c.setStartsAt(startsAt);
        c.setEndsAt(endsAt);
        c.setAudience(audience);
        c.setMinPreviousOrders(minPrev);
        c.setTotalUsageLimit(r.getTotalUsageLimit());
        c.setTotalBudget(budget);
        c.setPerCustomerLimit(r.getPerCustomerLimit());
        c.setPerCustomerPeriod(r.getPerCustomerPeriod());
    }

    private void validateCode(String code) {
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw bad("COUPON_CODE_INVALID",
                    "A coupon code is 3 to 40 characters: letters, digits, '-' or '_' (no spaces)");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public AdminCouponDto get(UUID id) {
        return toDto(load(id), null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminCouponDto> list(String status, String search, int page, int size) {
        String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        String q = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);

        Map<UUID, Long> uses = new HashMap<>();
        for (Object[] row : redemptionRepository.activeUsesPerCoupon()) {
            uses.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        Map<UUID, BigDecimal> budgetUsed = new HashMap<>();
        for (Object[] row : redemptionRepository.budgetUsedPerCoupon()) {
            budgetUsed.put((UUID) row[0], (BigDecimal) row[1]);
        }

        List<AdminCouponDto> all = new ArrayList<>();
        for (Coupon c : couponRepository.findAllNewestFirst()) {
            if (q != null && !(c.getCode().toLowerCase(Locale.ROOT).contains(q)
                    || (c.getDescription() != null && c.getDescription().toLowerCase(Locale.ROOT).contains(q)))) {
                continue;
            }
            AdminCouponDto dto = toDto(c, uses.getOrDefault(c.getId(), 0L),
                    budgetUsed.getOrDefault(c.getId(), BigDecimal.ZERO), -1L);
            if (wanted != null && !wanted.equals(dto.getStatus())) {
                continue;
            }
            all.add(dto);
        }

        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        int from = (int) Math.min((long) safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new PageImpl<>(new ArrayList<>(all.subList(from, to)), PageRequest.of(safePage, safeSize), all.size());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CouponRedemptionRowDto> redemptions(UUID id, int page, int size) {
        load(id);
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        long total = redemptionRepository.countAllForCoupon(id);
        List<CouponRedemptionRowDto> rows = new ArrayList<>();
        for (Object[] r : redemptionRepository.findRedemptionRows(id, safeSize, (long) safePage * safeSize)) {
            Instant releasedAt = toInstant(r[5]);
            rows.add(CouponRedemptionRowDto.builder()
                    .redemptionId((UUID) r[0])
                    .userId((UUID) r[1])
                    .phone((String) r[2])
                    .discount((BigDecimal) r[3])
                    .usedAt(toInstant(r[4]))
                    .released(releasedAt != null)
                    .releasedAt(releasedAt)
                    .orderNumbers(r[6] == null ? "" : r[6].toString())
                    .build());
        }
        return new PageImpl<>(rows, PageRequest.of(safePage, safeSize), total);
    }

    @Override
    @Transactional(readOnly = true)
    public String statusOfCode(String code) {
        String normalized = CouponCheckoutServiceImpl.normalize(code);
        if (normalized == null) {
            return null;
        }
        return couponRepository.findByCode(normalized).map(c -> toDto(c, null, null, -1L).getStatus()).orElse(null);
    }

    // ------------------------------------------------------------------------------------------------------
    // Switch / delete
    // ------------------------------------------------------------------------------------------------------

    @Override
    public AdminCouponDto setEnabled(UUID id, boolean enabled) {
        Coupon c = load(id);
        c.setEnabled(enabled);
        return toDto(couponRepository.save(c), null, null, null);
    }

    @Override
    public void delete(UUID id) {
        Coupon c = load(id);
        if (redemptionRepository.countAllForCoupon(id) > 0) {
            throw bad("COUPON_ALREADY_USED", "This coupon has been used and cannot be deleted. Switch it off instead.");
        }
        selectionRepository.deleteByCoupon(id); // customers holding it simply no longer hold anything
        c.softDelete();
        couponRepository.save(c);
    }

    // ------------------------------------------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------------------------------------------

    /**
     * @param usesOrNull       pre-computed uses (list view) or null to query
     * @param budgetUsedOrNull pre-computed budget used or null to query
     * @param customersOrNull  -1 = skip the (per-coupon) customer count in list view, null = query
     */
    private AdminCouponDto toDto(Coupon c, Long usesOrNull, BigDecimal budgetUsedOrNull, Long customersOrNull) {
        long uses = usesOrNull != null ? usesOrNull : redemptionRepository.countActiveUses(c.getId());
        BigDecimal budgetUsed = budgetUsedOrNull != null ? budgetUsedOrNull : redemptionRepository.sumBudgetUsed(c.getId());
        if (budgetUsed == null) {
            budgetUsed = BigDecimal.ZERO;
        }
        budgetUsed = budgetUsed.setScale(2, RoundingMode.HALF_UP);
        long customers = customersOrNull == null ? redemptionRepository.countActiveCustomers(c.getId())
                : Math.max(customersOrNull, 0L);

        Long usesLeft = c.getTotalUsageLimit() == null ? null : Math.max(0L, c.getTotalUsageLimit() - uses);
        BigDecimal budgetLeft = c.getTotalBudget() == null ? null
                : c.getTotalBudget().subtract(budgetUsed).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        return AdminCouponDto.builder()
                .id(c.getId())
                .code(c.getCode())
                .description(c.getDescription())
                .discountType(c.getDiscountType())
                .discountValue(c.getDiscountValue())
                .maxDiscount(c.getMaxDiscount())
                .minOrderAmount(c.getMinOrderAmount())
                .startsAt(c.getStartsAt())
                .endsAt(c.getEndsAt())
                .startsAtIst(ist(c.getStartsAt()))
                .endsAtIst(ist(c.getEndsAt()))
                .audience(c.getAudience())
                .minPreviousOrders(c.getMinPreviousOrders())
                .totalUsageLimit(c.getTotalUsageLimit())
                .totalBudget(c.getTotalBudget())
                .perCustomerLimit(c.getPerCustomerLimit())
                .perCustomerPeriod(c.getPerCustomerPeriod())
                .listed(c.isListed())
                .enabled(c.isEnabled())
                .status(statusOf(c, usesLeft, budgetLeft))
                .usesCount(uses)
                .usesLeft(usesLeft)
                .budgetUsed(budgetUsed)
                .budgetLeft(budgetLeft)
                .customersCount(customers)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    /** DISABLED, then EXPIRED, then SCHEDULED, then USED_UP, else LIVE. */
    String statusOf(Coupon c, Long usesLeft, BigDecimal budgetLeft) {
        Instant now = now();
        if (!c.isEnabled()) {
            return "DISABLED";
        }
        if (c.getEndsAt() != null && !now.isBefore(c.getEndsAt())) {
            return "EXPIRED";
        }
        if (c.getStartsAt() != null && now.isBefore(c.getStartsAt())) {
            return "SCHEDULED";
        }
        if ((usesLeft != null && usesLeft <= 0) || (budgetLeft != null && budgetLeft.signum() <= 0)) {
            return "USED_UP";
        }
        return "LIVE";
    }

    // ------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------

    private Coupon load(UUID id) {
        return couponRepository.findById(id)
                .orElseThrow(() -> new BusinessException("COUPON_NOT_FOUND", "Coupon not found", HttpStatus.NOT_FOUND));
    }

    private static BusinessException bad(String code, String message) {
        return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }

    private static String ist(Instant i) {
        return i == null ? null : IST_FORMAT.format(i.atZone(CouponWindows.IST));
    }

    /**
     * "2026-11-01T00:00:00" = Indian time; "2026-11-01T00:00:00Z" / "+05:30" = as given. Blank = none.
     */
    static Instant parseDateTime(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        try {
            if (t.endsWith("Z") || t.matches(".*[+-]\\d{2}:\\d{2}$")) {
                return OffsetDateTime.parse(t).toInstant();
            }
            return LocalDateTime.parse(t).atZone(CouponWindows.IST).toInstant();
        } catch (DateTimeParseException e) {
            throw bad("COUPON_DATES_INVALID",
                    field + " must be an ISO date-time such as 2026-11-01T00:00:00 (Indian time)");
        }
    }

    /** Native timestamp columns come back as Timestamp, LocalDateTime or Instant depending on the driver. */
    static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant) {
            return (Instant) value;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
        }
        if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
    }
}
