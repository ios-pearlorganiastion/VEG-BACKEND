package com.veggofresh.coupon.service;

import com.veggofresh.coupon.dto.CouponPreview;
import com.veggofresh.coupon.dto.CouponQuote;
import com.veggofresh.coupon.dto.CouponReservation;
import com.veggofresh.coupon.dto.CustomerCouponDto;
import com.veggofresh.coupon.entity.Coupon;
import com.veggofresh.coupon.entity.CouponAudience;
import com.veggofresh.coupon.entity.CouponRedemption;
import com.veggofresh.coupon.entity.CouponRedemptionOrder;
import com.veggofresh.coupon.entity.CouponType;
import com.veggofresh.coupon.entity.CustomerCouponSelection;
import com.veggofresh.coupon.repository.CouponRedemptionOrderRepository;
import com.veggofresh.coupon.repository.CouponRedemptionRepository;
import com.veggofresh.coupon.repository.CouponRepository;
import com.veggofresh.coupon.repository.CustomerCouponSelectionRepository;
import com.veggofresh.coupon.service.CouponMath.Basis;
import com.veggofresh.platform.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CouponCheckoutServiceImpl implements CouponCheckoutService {

    static final String INVALID_CODE = "INVALID_PROMO_CODE";
    static final String INVALID_MESSAGE = "The promo code is invalid or does not meet criteria";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(CouponWindows.IST);

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final CouponRedemptionOrderRepository redemptionOrderRepository;
    private final CustomerCouponSelectionRepository selectionRepository;

    /** Seam for tests. */
    Instant now() {
        return Instant.now();
    }

    // ------------------------------------------------------------------------------------------------------
    // Offers list
    // ------------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<CustomerCouponDto> listOffers(UUID userId) {
        Instant now = now();
        UUID heldId = selectionRepository.findByUserId(userId).map(CustomerCouponSelection::getCouponId).orElse(null);
        LazyLong customerOrders = new LazyLong(() -> redemptionRepository.countCustomerOrders(userId));

        List<CustomerCouponDto> out = new ArrayList<>();
        for (Coupon c : couponRepository.findListedOffers(now)) {
            Failure f = checkStatic(c, userId, now, customerOrders);
            out.add(CustomerCouponDto.builder()
                    .code(c.getCode())
                    .description(c.getDescription())
                    .discountType(c.getDiscountType())
                    .discountValue(c.getDiscountValue())
                    .maxDiscount(c.getMaxDiscount())
                    .minOrderAmount(c.getMinOrderAmount())
                    .validFrom(c.getStartsAt())
                    .validUntil(c.getEndsAt())
                    .audience(c.getAudience())
                    .usable(f == null)
                    .reasonCode(f == null ? null : f.code())
                    .reason(f == null ? null : f.message())
                    .selected(c.getId().equals(heldId))
                    .build());
        }
        // Usable first; the list is already newest first and the sort is stable.
        out.sort((a, b) -> Boolean.compare(b.isUsable(), a.isUsable()));
        return out;
    }

    // ------------------------------------------------------------------------------------------------------
    // Holding a coupon
    // ------------------------------------------------------------------------------------------------------

    @Override
    public void select(UUID userId, String code) {
        String normalized = normalize(code);
        if (normalized == null) {
            throw new BusinessException("PROMO_CODE_REQUIRED", "Promo code is required", HttpStatus.BAD_REQUEST);
        }
        Coupon coupon = couponRepository.findByCode(normalized)
                .orElseThrow(() -> new BusinessException(INVALID_CODE, INVALID_MESSAGE, HttpStatus.BAD_REQUEST));
        Failure f = checkStatic(coupon, userId, now(), new LazyLong(() -> redemptionRepository.countCustomerOrders(userId)));
        if (f != null) {
            throw new BusinessException(f.code(), f.message(), HttpStatus.BAD_REQUEST);
        }
        CustomerCouponSelection selection = selectionRepository.findByUserId(userId).orElseGet(() -> {
            CustomerCouponSelection s = new CustomerCouponSelection();
            s.setUserId(userId);
            return s;
        });
        selection.setCouponId(coupon.getId());
        selectionRepository.save(selection);
    }

    @Override
    public void clearSelection(UUID userId) {
        selectionRepository.findByUserId(userId).ifPresent(selectionRepository::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<String> usableCodes(UUID userId, java.util.Collection<String> codes) {
        java.util.Set<String> usable = new java.util.LinkedHashSet<>();
        Instant now = now();
        LazyLong customerOrders = new LazyLong(() -> redemptionRepository.countCustomerOrders(userId));
        for (String raw : codes) {
            String code = normalize(raw);
            if (code == null || usable.contains(code)) {
                continue;
            }
            Optional<Coupon> coupon = couponRepository.findByCode(code);
            if (coupon.isPresent() && checkStatic(coupon.get(), userId, now, customerOrders) == null) {
                usable.add(code);
            }
        }
        return usable;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean holdsCoupon(UUID userId) {
        return selectionRepository.findByUserId(userId).isPresent();
    }

    // ------------------------------------------------------------------------------------------------------
    // Valuing the held coupon
    // ------------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CouponPreview previewEach(UUID userId, List<Basis> carts) {
        Held held = resolveHeld(userId, null);
        if (held == null) {
            return CouponPreview.none();
        }
        if (held.stale()) {
            return new CouponPreview(null, "The coupon you selected is no longer available.",
                    java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
        }
        Instant now = now();
        Coupon c = couponRepository.findByCode(held.code()).orElse(null);
        Failure f = c == null ? new Failure(INVALID_CODE, INVALID_MESSAGE)
                : checkStatic(c, userId, now, new LazyLong(() -> redemptionRepository.countCustomerOrders(userId)));
        if (f != null) {
            return new CouponPreview(null, f.message(), java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
        }

        BigDecimal remaining = remainingBudget(c);
        Map<UUID, BigDecimal> discounts = new LinkedHashMap<>();
        Map<UUID, String> messages = new LinkedHashMap<>();
        for (Basis b : carts) {
            Fit fit = fit(c, List.of(b), remaining, carts.size() > 1);
            discounts.put(b.key(), fit.result() == null ? BigDecimal.ZERO : fit.result().total());
            if (fit.message() != null) {
                messages.put(b.key(), fit.message());
            }
        }
        return new CouponPreview(c.getCode(), null, discounts, messages);
    }

    @Override
    @Transactional(readOnly = true)
    public CouponQuote quote(UUID userId, String explicitCode, List<Basis> carts) {
        return evaluate(userId, explicitCode, carts, false);
    }

    @Override
    public CouponReservation reserve(UUID userId, String explicitCode, List<Basis> carts) {
        CouponQuote q = evaluate(userId, explicitCode, carts, true);
        switch (q.status()) {
            case NONE:
                return CouponReservation.none();
            case INVALID:
                // Stop here: nothing has been charged, and the customer is told why instead of silently paying full price.
                throw new BusinessException(q.errorCode(), q.message(), HttpStatus.BAD_REQUEST);
            case NOT_APPLICABLE:
                return CouponReservation.notApplied(q.code(), q.message());
            case APPLIED:
            default:
                break;
        }

        CouponRedemption redemption = new CouponRedemption();
        redemption.setCouponId(q.couponId());
        redemption.setUserId(userId);
        redemption.setTotalDiscount(q.total());
        redemption = redemptionRepository.save(redemption);

        // The coupon the customer was holding has now been used.
        Optional<CustomerCouponSelection> held = selectionRepository.findByUserId(userId);
        if (held.isPresent() && q.couponId().equals(held.get().getCouponId())) {
            selectionRepository.delete(held.get());
        }
        log.info("Coupon {} reserved for user {}: {} (redemption {})", q.code(), userId, q.total(), redemption.getId());
        return new CouponReservation(redemption.getId(), q.code(), q.total(), q.shares(), q.message());
    }

    @Override
    public void linkOrders(UUID redemptionId, Map<UUID, BigDecimal> shareByOrderId) {
        CouponRedemption redemption = redemptionRepository.findById(redemptionId)
                .orElseThrow(() -> new IllegalStateException("Coupon redemption " + redemptionId + " not found"));
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> e : shareByOrderId.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() <= 0) {
                continue; // an order with no share carries nothing to give back
            }
            CouponRedemptionOrder row = new CouponRedemptionOrder();
            row.setRedemptionId(redemptionId);
            row.setOrderId(e.getKey());
            row.setDiscountAmount(e.getValue());
            redemptionOrderRepository.save(row);
            sum = sum.add(e.getValue());
        }
        if (sum.compareTo(redemption.getTotalDiscount()) != 0) {
            // Rolls the whole checkout back rather than leave orders and redemption disagreeing about money.
            throw new IllegalStateException("Coupon shares " + sum + " do not add up to the redemption total "
                    + redemption.getTotalDiscount());
        }
    }

    @Override
    public void onOrderCancelled(UUID orderId) {
        Optional<CouponRedemptionOrder> share = redemptionOrderRepository.findByOrderId(orderId);
        if (share.isEmpty() || share.get().getCancelledAt() != null) {
            return; // no coupon on this order, or already given back
        }
        Instant now = now();
        CouponRedemptionOrder row = share.get();
        row.setCancelledAt(now);
        redemptionOrderRepository.save(row);

        if (redemptionOrderRepository.countByRedemptionIdAndCancelledAtIsNull(row.getRedemptionId()) == 0) {
            redemptionRepository.findById(row.getRedemptionId()).ifPresent(r -> {
                if (r.getReleasedAt() == null) {
                    r.setReleasedAt(now);
                    redemptionRepository.save(r);
                    log.info("Coupon use {} given back: every order of the checkout was cancelled", r.getId());
                }
            });
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------------------

    private record Failure(String code, String message) {
    }

    /** The coupon a customer is using: a typed/explicit code, else the one held. {@code stale} = held but gone. */
    private record Held(String code, boolean stale) {
    }

    /** What one valuation produced (the result is null when the cart is below the minimum order). */
    private record Fit(CouponQuote.Status status, String errorCode, String message, CouponMath.Result result) {
    }

    private Held resolveHeld(UUID userId, String explicitCode) {
        String explicit = normalize(explicitCode);
        if (explicit != null) {
            return new Held(explicit, false);
        }
        Optional<CustomerCouponSelection> selection = selectionRepository.findByUserId(userId);
        if (selection.isEmpty()) {
            return null;
        }
        return couponRepository.findById(selection.get().getCouponId())
                .map(c -> new Held(c.getCode(), false))
                .orElse(new Held(null, true));
    }

    private CouponQuote evaluate(UUID userId, String explicitCode, List<Basis> carts, boolean lock) {
        Held held = resolveHeld(userId, explicitCode);
        if (held == null) {
            return CouponQuote.none();
        }
        if (held.stale()) {
            return CouponQuote.invalid(null, INVALID_CODE, "The coupon you selected is no longer available.");
        }
        Optional<Coupon> found = lock ? couponRepository.findByCodeForUpdate(held.code())
                : couponRepository.findByCode(held.code());
        if (found.isEmpty()) {
            return CouponQuote.invalid(held.code(), INVALID_CODE, INVALID_MESSAGE);
        }
        Coupon c = found.get();
        Failure f = checkStatic(c, userId, now(), new LazyLong(() -> redemptionRepository.countCustomerOrders(userId)));
        if (f != null) {
            return CouponQuote.invalid(c.getCode(), f.code(), f.message());
        }

        Fit fit = fit(c, carts, remainingBudget(c), false);
        switch (fit.status()) {
            case NOT_APPLICABLE:
                return CouponQuote.notApplicable(c.getId(), c.getCode(), fit.errorCode(), fit.message());
            case INVALID:
                return CouponQuote.invalid(c.getCode(), fit.errorCode(), fit.message());
            default:
                return CouponQuote.applied(c.getId(), c.getCode(), fit.message(), fit.result().total(),
                        fit.result().shares());
        }
    }

    /**
     * Values {@code c} for {@code carts} paid together. {@code preview} wording is for the cart screen, where a cart
     * is valued alone and may qualify once checked out together with the others.
     */
    private Fit fit(Coupon c, List<Basis> carts, BigDecimal remainingBudget, boolean mentionTogether) {
        BigDecimal items = BigDecimal.ZERO;
        for (Basis b : carts) {
            items = items.add(b.items());
        }
        if (carts.isEmpty() || items.signum() <= 0) {
            return new Fit(CouponQuote.Status.NOT_APPLICABLE, "COUPON_NOT_APPLICABLE",
                    "This coupon doesn't apply to your items.", null);
        }
        BigDecimal min = c.getMinOrderAmount() == null ? BigDecimal.ZERO : c.getMinOrderAmount();
        if (items.compareTo(min) < 0) {
            String more = Money.inr(min.subtract(items));
            String msg = "Add " + more + " more to get this discount (minimum order " + Money.inr(min) + ")"
                    + (mentionTogether ? ", or check out together with your other carts." : ".");
            return new Fit(CouponQuote.Status.NOT_APPLICABLE, "COUPON_MIN_ORDER_NOT_MET", msg, null);
        }

        CouponMath.Result r = CouponMath.compute(c.getDiscountType() == CouponType.PERCENT, c.getDiscountValue(),
                c.getMaxDiscount(), remainingBudget, carts);
        if (r.total().signum() <= 0) {
            if (r.limitedBy() == CouponMath.Limit.BUDGET) {
                return new Fit(CouponQuote.Status.INVALID, "COUPON_BUDGET_EXHAUSTED",
                        "This coupon is no longer available.", r);
            }
            return new Fit(CouponQuote.Status.NOT_APPLICABLE, "COUPON_NOT_AVAILABLE_FOR_ITEMS",
                    "This coupon isn't available for the items in this order.", r);
        }
        String note = null;
        if (r.limitedBy() == CouponMath.Limit.MARGIN || r.limitedBy() == CouponMath.Limit.BUDGET) {
            // Deliberately vague: the customer is never told about margins or budgets.
            note = "The discount available on these items is " + Money.inr(r.total()) + ".";
        }
        return new Fit(CouponQuote.Status.APPLIED, null, note, r);
    }

    /** What is left of the coupon's total budget, or null when it has none. */
    private BigDecimal remainingBudget(Coupon c) {
        if (c.getTotalBudget() == null) {
            return null;
        }
        BigDecimal used = redemptionRepository.sumBudgetUsed(c.getId());
        return c.getTotalBudget().subtract(used == null ? BigDecimal.ZERO : used);
    }

    /**
     * The rules that do NOT depend on the amount: is it on, started, not ended, allowed for this customer, uses
     * left, budget left, this customer's own limit not reached. Null = fine.
     */
    private Failure checkStatic(Coupon c, UUID userId, Instant now, LongSupplier customerOrders) {
        if (!c.isEnabled()) {
            return new Failure(INVALID_CODE, INVALID_MESSAGE);
        }
        if (c.getStartsAt() != null && now.isBefore(c.getStartsAt())) {
            return new Failure("COUPON_NOT_STARTED",
                    "This coupon is not active yet. It starts on " + WHEN.format(c.getStartsAt()) + ".");
        }
        if (c.getEndsAt() != null && !now.isBefore(c.getEndsAt())) {
            return new Failure("COUPON_EXPIRED", "This coupon has expired.");
        }

        CouponAudience audience = c.getAudience() == null ? CouponAudience.EVERYONE : c.getAudience();
        if (audience == CouponAudience.NEW_CUSTOMERS && customerOrders.getAsLong() > 0) {
            return new Failure("COUPON_NOT_FOR_YOU", "This coupon is only for new customers.");
        }
        if (audience == CouponAudience.RETURNING_CUSTOMERS) {
            long needed = Math.max(1, c.getMinPreviousOrders());
            if (customerOrders.getAsLong() < needed) {
                return new Failure("COUPON_NOT_FOR_YOU", needed == 1
                        ? "This coupon is for returning customers."
                        : "This coupon is for customers with at least " + needed + " earlier orders.");
            }
        }

        if (c.getTotalUsageLimit() != null && redemptionRepository.countActiveUses(c.getId()) >= c.getTotalUsageLimit()) {
            return new Failure("COUPON_USAGE_LIMIT_REACHED", "This coupon has been fully claimed.");
        }
        if (c.getTotalBudget() != null) {
            BigDecimal remaining = remainingBudget(c);
            if (remaining.signum() <= 0) {
                return new Failure("COUPON_BUDGET_EXHAUSTED", "This coupon has been fully claimed.");
            }
        }
        if (c.getPerCustomerLimit() != null && c.getPerCustomerPeriod() != null) {
            CouponWindows.Window w = CouponWindows.of(c.getPerCustomerPeriod(), now);
            long used = redemptionRepository.countActiveUsesByCustomer(c.getId(), userId, w.from(), w.to());
            if (used >= c.getPerCustomerLimit()) {
                String when = CouponWindows.describe(c.getPerCustomerPeriod());
                return new Failure("COUPON_CUSTOMER_LIMIT_REACHED", when.isEmpty()
                        ? "You have already used this coupon the maximum number of times."
                        : "You have already used this coupon the maximum number of times " + when + ".");
            }
        }
        return null;
    }

    static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String t = code.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    /** A value computed on first use, so a query is only run when a rule actually needs it. */
    private static final class LazyLong implements LongSupplier {
        private final LongSupplier source;
        private Long value;

        LazyLong(LongSupplier source) {
            this.source = source;
        }

        @Override
        public long getAsLong() {
            if (value == null) {
                value = source.getAsLong();
            }
            return value;
        }
    }
}
