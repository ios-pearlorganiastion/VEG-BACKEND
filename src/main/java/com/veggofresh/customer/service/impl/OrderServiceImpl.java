package com.veggofresh.customer.service.impl;

import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.coupon.dto.CouponQuote;
import com.veggofresh.coupon.dto.CouponReservation;
import com.veggofresh.coupon.service.CouponCheckoutService;
import com.veggofresh.coupon.service.CouponMath;
import com.veggofresh.auth.service.UserLookupService;
import com.veggofresh.customer.dto.request.CartItemRequestDto;
import com.veggofresh.customer.dto.request.OrderRequestDto;
import com.veggofresh.customer.dto.request.RatingRequestDto;
import com.veggofresh.customer.dto.response.CartCheckoutBreakdownDto;
import com.veggofresh.customer.dto.response.CartResponseDto;
import com.veggofresh.customer.dto.response.CheckoutIssueDto;
import com.veggofresh.customer.dto.response.CheckoutResultDto;
import com.veggofresh.customer.dto.response.CheckoutSummaryDto;
import com.veggofresh.customer.dto.response.InvoiceDto;
import com.veggofresh.customer.dto.response.InvoiceLineItemDto;
import com.veggofresh.customer.dto.response.OrderItemResponseDto;
import com.veggofresh.customer.dto.response.OrderResponseDto;
import com.veggofresh.customer.dto.response.OrderTrackingResponseDto;
import com.veggofresh.customer.dto.response.RatingResponseDto;
import com.veggofresh.customer.dto.response.StatusTimelineDto;
import com.veggofresh.customer.entity.Address;
import com.veggofresh.customer.entity.Cart;
import com.veggofresh.customer.entity.CartItem;
import com.veggofresh.customer.entity.CustomerProfile;
import com.veggofresh.customer.entity.DeliverySlot;
import com.veggofresh.customer.entity.Order;
import com.veggofresh.customer.entity.OrderItem;
import com.veggofresh.customer.entity.OrderStatus;
import com.veggofresh.customer.entity.Rating;
import com.veggofresh.customer.repository.AddressRepository;
import com.veggofresh.customer.repository.CartRepository;
import com.veggofresh.customer.repository.CustomerProfileRepository;
import com.veggofresh.customer.repository.DeliverySlotRepository;
import com.veggofresh.customer.repository.OrderItemRepository;
import com.veggofresh.customer.repository.OrderRepository;
import com.veggofresh.customer.repository.RatingRepository;
import com.veggofresh.customer.service.CartService;
import com.veggofresh.customer.service.CartVendorResolver;
import com.veggofresh.customer.service.OrderService;
import com.veggofresh.notification.entity.NotificationRecipientRole;
import com.veggofresh.notification.entity.NotificationType;
import com.veggofresh.notification.service.NotificationService;
import com.veggofresh.payment.dto.PaymentHoldResponseDto;
import com.veggofresh.payment.service.PaymentService;
import com.veggofresh.payment.service.WalletService;
import com.veggofresh.payment.service.WalletTransactionReason;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.dto.ProductDto;
import com.veggofresh.vendor.service.ProductCatalogService;
import com.veggofresh.vendor.service.ShopLookupService;
import com.veggofresh.admin.service.AdminProductService;
import com.veggofresh.admin.service.PlatformSettingsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PHASE 2 — NEW ARCHITECTURE, multi-cart / one-payment-to-N-orders checkout
 * (PROJECT_STATE section 2).
 *
 * VENDOR CATALOG PIVOT PATCH: ProductCatalogService methods now require a
 * latitude/longitude for radius eligibility. Every call site here uses a
 * location already in scope -- the resolved checkout Address in
 * checkout()/buildOrderFromCart()/getCheckoutSummary(), or the Order's own
 * stored delivery latitude/longitude in trackOrder()/getInvoice()/reorder().
 * No new address lookups were needed. See NOTES_CUSTOMER.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrderServiceImpl implements OrderService {

    /** Human-facing prefix, so an order number is recognisable in support chats. */
    private static final String ORDER_NUMBER_PREFIX = "#DM-";

    /**
     * Number of random base-36 characters after the prefix. 15 characters is
     * 15 * log2(36) ~= 77.1 bits of entropy, which puts the birthday-bound
     * collision point at roughly 10^11 orders -- far beyond any realistic table
     * size, and well past the 20-character column limit with room to spare.
     * See {@link #nextOrderNumber()}.
     */
    private static final int ORDER_NUMBER_RANDOM_CHARS = 15;

    private static final char[] BASE36_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /**
     * Shared CSPRNG. Deliberately static and thread-safe ({@link SecureRandom}
     * is), so concurrent checkouts draw from one source without locking, and
     * unlike a per-node counter the draws carry no shared state between
     * application instances.
     */
    private static final SecureRandom ORDER_NUMBER_RANDOM = new SecureRandom();

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final RatingRepository ratingRepository;
    private final DeliverySlotRepository deliverySlotRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final ProductCatalogService productCatalogService;
    private final AdminProductService adminProductService;
    private final CartService cartService;
    private final UserLookupService userLookupService;
    private final OrderResponseMapper orderResponseMapper;
    private final WalletService walletService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final ShopLookupService shopLookupService;
    private final PlatformSettingsService platformSettingsService;
    private final CartVendorResolver cartVendorResolver;
    private final CouponCheckoutService couponService;

    @Override
    public CheckoutResultDto checkout(UUID userId, OrderRequestDto request) {
        Address address = addressRepository.findByIdAndUserId(request.getAddressId(), userId)
                .orElseThrow(() -> new BusinessException("ADDRESS_NOT_FOUND", "Invalid address selected",
                        HttpStatus.BAD_REQUEST));

        List<Cart> openCarts = shoppableCarts(userId, newVendorSession(address));
        if (openCarts.isEmpty()) {
            throw new BusinessException("CART_EMPTY", "You have no items in any cart", HttpStatus.BAD_REQUEST);
        }

        DeliverySlot slot = null;
        if (request.getDeliverySlotId() != null) {
            slot = deliverySlotRepository.findById(request.getDeliverySlotId())
                    .orElseThrow(() -> new BusinessException("DELIVERY_SLOT_NOT_FOUND",
                            "Selected delivery slot is invalid", HttpStatus.BAD_REQUEST));
        }

        List<OrderResponseDto> createdOrders = new ArrayList<>();
        List<CheckoutIssueDto> issues = new ArrayList<>();
        List<Order> placedOrders = new ArrayList<>();
        List<Order> toPlace = new ArrayList<>();
        List<UUID> toPlaceCartIds = new ArrayList<>();

        // ── Selective checkout ──
        // cartIds null/empty -> every open cart (backward compatible).
        // cartIds non-empty  -> only those carts; the rest stay open and are
        //                        neither ordered nor cleared below.
        final List<UUID> requestedCartIds = (request.getCartIds() != null && !request.getCartIds().isEmpty())
                ? request.getCartIds()
                : null;

        if (requestedCartIds != null && openCarts.stream().noneMatch(c -> requestedCartIds.contains(c.getId()))) {
            throw new BusinessException("CART_NOT_FOUND",
                    "None of the specified cart IDs were found in your open carts",
                    HttpStatus.BAD_REQUEST);
        }

        for (int i = 0; i < openCarts.size(); i++) {
            Cart cart = openCarts.get(i);

            // Skip unselected carts WITHOUT consuming a label: "Cart N" must
            // keep matching the cart screen, so checking out only Cart 2 still
            // reports it as "Cart 2" rather than renumbering it to "Cart 1".
            if (requestedCartIds != null && !requestedCartIds.contains(cart.getId())) {
                continue;
            }

            // One shared numbering, identical to the cart screen and to
            // getCheckoutSummary. Previously a local counter also advanced past
            // skipped carts, so a cart could be labelled "Cart 2" here and
            // "Cart 1" on the screen — and the client ties these together.
            String cartLabel = CartServiceImpl.cartLabel(i);

            // Re-validate vendor overlap fresh at checkout time — a cart's
            // overlap may have broken since add-time even if untouched
            // (PROJECT_STATE section 2, "Revisit-after-a-delay edge case").
            CartVendorResolver.Session vendors = newVendorSession(address);
            if (!vendors.revalidate(cart)) {
                issues.add(issue(cart, cartLabel,
                        "Some items in this group are no longer available together — remove them to continue, or we'll leave this group out of your order"));
                continue;
            }

            // A cart can pass the overlap check and still have nothing buyable
            // left (every product delisted or out of range). Building an order
            // from it would charge a delivery fee and platform fee for zero
            // items, so it is reported as an issue and skipped instead.
            List<CartItem> shippable = vendors.onlyShippableItems(cart);
            if (shippable.isEmpty()) {
                issues.add(issue(cart, cartLabel,
                        "None of the items in this group are available for delivery to this address — we'll leave this group out of your order"));
                continue;
            }

            // Built here but only saved below: the coupon is one coupon for the whole checkout, so it can only be
            // valued once every order of this checkout is known.
            toPlace.add(buildOrderFromCart(userId, cart, address, slot, request, vendors.effectiveVendorIds(cart),
                    shippable));
            toPlaceCartIds.add(cart.getId());
        }

        if (toPlace.isEmpty()) {
            throw new BusinessException("CHECKOUT_FAILED",
                    "None of your carts could be checked out — please review the issues", HttpStatus.BAD_REQUEST);
        }

        // ── One coupon for the whole checkout (single cart, several carts or all carts) ──
        // Re-validated from scratch under a lock on the coupon, and the use is recorded in this same transaction.
        // A dead coupon stops the checkout here with a clear message (nothing has been charged); a coupon that
        // simply does not fit these carts (minimum order, no margin) gives no discount and is kept by the customer.
        List<CouponMath.Basis> bases = new ArrayList<>(toPlace.size());
        for (Order order : toPlace) {
            bases.add(couponBasis(order));
        }
        CouponReservation coupon = couponService.reserve(userId, request.getCouponCode(), bases);
        for (Order order : toPlace) {
            BigDecimal share = coupon.shareOf(order.getSourceCartId());
            if (share.signum() > 0) {
                applyPromo(order, coupon.code(), share);
            }
        }

        Map<UUID, BigDecimal> shareByOrderId = new LinkedHashMap<>();
        for (int i = 0; i < toPlace.size(); i++) {
            Order saved = orderRepository.save(toPlace.get(i));
            createdOrders.add(orderResponseMapper.mapToDto(saved));
            placedOrders.add(saved);
            BigDecimal share = coupon.shareOf(saved.getSourceCartId());
            if (share.signum() > 0) {
                shareByOrderId.put(saved.getId(), share);
            }

            cartService.clearCart(userId, toPlaceCartIds.get(i));
        }
        if (coupon.hasDiscount()) {
            couponService.linkOrders(coupon.redemptionId(), shareByOrderId);
        }

        // PAYMENT INTEGRATION: create a single Razorpay order (hold) covering all
        // successfully checked-out orders in this call. The frontend uses
        // razorpayOrderId + razorpayKeyId to open Razorpay Checkout.js.
        List<UUID> orderIds = createdOrders.stream()
                .map(OrderResponseDto::getId)
                .collect(Collectors.toList());
        List<java.math.BigDecimal> orderAmounts = createdOrders.stream()
                .map(OrderResponseDto::getTotalAmount)
                .collect(Collectors.toList());
        PaymentHoldResponseDto paymentHold = paymentService.createHold(userId, orderIds, orderAmounts);

        // Order-placed notifications fire only AFTER the payment hold succeeded
        // so they never outlive a rolled-back checkout.
        placedOrders.forEach(order -> notifyOrderPlaced(userId, order));

        log.info("Checkout complete: {} order(s) created (from {} cart(s) requested), razorpay order={}, total={}, {} cart(s) skipped",
                createdOrders.size(),
                requestedCartIds != null ? requestedCartIds.size() : "all",
                paymentHold.getRazorpayOrderId(), paymentHold.getTotalAmount(), issues.size());

        return CheckoutResultDto.builder()
                .orders(createdOrders)
                .issues(issues)
                .paymentHold(paymentHold)
                .promoMessage(coupon.message())
                .build();
    }

    /** What an order sells for and what the platform earns on it (selling price minus vendor price). */
    private CouponMath.Basis couponBasis(Order order) {
        BigDecimal items = BigDecimal.ZERO;
        BigDecimal margin = BigDecimal.ZERO;
        for (OrderItem item : order.getItems()) {
            BigDecimal qty = BigDecimal.valueOf(item.getQuantity());
            items = items.add(item.getPrice().multiply(qty));
            BigDecimal vendorPrice = item.getVendorUnitPrice();
            if (vendorPrice != null && vendorPrice.compareTo(item.getPrice()) < 0) {
                margin = margin.add(item.getPrice().subtract(vendorPrice).multiply(qty));
            }
        }
        return new CouponMath.Basis(order.getSourceCartId(), items, margin);
    }

    /** Puts this order's share of the checkout's coupon on it. The share is at most the items total. */
    private void applyPromo(Order order, String code, BigDecimal discount) {
        order.setPromoCode(code);
        order.setPromoDiscount(discount);
        BigDecimal total = order.getTotalAmount().subtract(discount);
        order.setTotalAmount(total.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : total);
    }

    private CheckoutIssueDto issue(Cart cart, String cartLabel, String reason) {
        return CheckoutIssueDto.builder()
                .cartId(cart.getId())
                .cartLabel(cartLabel)
                .reason(reason)
                .build();
    }

    /**
     * Open carts that still hold at least one item, oldest first. Empty carts
     * are skipped so that they cannot consume a "Cart N" label and desync the
     * numbering from the cart screen. Writers should have retired them already
     * (see CartServiceImpl); this is the read-side guard.
     */
    private List<Cart> shoppableCarts(UUID userId, CartVendorResolver.Session vendors) {
        return vendors.visibleCarts(cartRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId));
    }

    private CartVendorResolver.Session newVendorSession(Address address) {
        return cartVendorResolver.newSession(address.getLatitude(), address.getLongitude());
    }

    private Order buildOrderFromCart(UUID userId, Cart cart, Address address, DeliverySlot slot,
            OrderRequestDto request, Set<UUID> resolvedVendorIds, List<CartItem> shippableItems) {
        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.PLACED);
        order.setDeliveryAddress(address.getAddressLine1() + ", " + address.getCity() + ", " + address.getState()
                + " - " + address.getPostalCode());
        order.setLatitude(address.getLatitude());
        order.setLongitude(address.getLongitude());
        order.setOrderNumber(nextOrderNumber());
        order.setSourceCartId(cart.getId());
        order.setCandidateVendorIds(new HashSet<>(resolvedVendorIds));

        BigDecimal subtotal = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();

        // Only the shippable items are converted, and only for THIS cart. The
        // previous version threw on the first unresolvable product, which
        // propagated out of the whole checkout loop: one stale line in Cart 1
        // used to abort checkout for Cart 2 and Cart 3 as well, and the
        // customer got a 400 for carts that were perfectly fine.
        CartVendorResolver.Session vendors = newVendorSession(address);
        // Vendor price per product (what the vendor is paid), snapshotted onto each line below.
        Map<UUID, BigDecimal> vendorPrices = adminProductService.getVendorPrices(
                shippableItems.stream().map(CartItem::getProductId).collect(Collectors.toSet()));
        for (CartItem item : shippableItems) {
            ProductDto product = safeGetProduct(item.getProductId(), vendors);
            if (product == null || product.getPrice() == null) {
                continue;
            }

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(item.getProductId());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setPrice(product.getPrice());
            // Never above the selling price (so the margin can never be negative), and never null.
            BigDecimal vendorPrice = vendorPrices.get(item.getProductId());
            orderItem.setVendorUnitPrice(vendorPrice == null || vendorPrice.compareTo(product.getPrice()) > 0
                    ? product.getPrice() : vendorPrice);
            orderItem.setUnit(product.getUnit());
            orderItems.add(orderItem);

            subtotal = subtotal.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        // A fee-only order is never acceptable. The delivery and platform fees
        // below are added unconditionally, so an order whose line items all
        // dropped out (product deactivated between the shippability guard and
        // here, a null price, or any future divergence between the availability
        // predicate and the pricing one) would be persisted with no items and a
        // total of deliveryFee + platformFee -- charging the customer real money
        // for an empty order, then soft-deleting the cart so it cannot be
        // recovered. Refuse to build the order instead. Deliberately throws
        // rather than skipping the cart: the cart was explicitly selected, and
        // silently dropping it would hand back fewer orders than the customer
        // confirmed.
        if (orderItems.isEmpty()) {
            throw new BusinessException("CART_EMPTY",
                    "No items in this cart are currently available to order", HttpStatus.CONFLICT);
        }

        order.setItems(orderItems);

        if (slot != null) {
            order.setDeliveryTimeSlot(slot.getLabel());
            order.setScheduledDate(
                    request.getScheduledDate() != null ? LocalDate.parse(request.getScheduledDate()) : slot.getDate());
        }

        if (request.getPaymentMethodId() != null) {
            order.setPaymentMethodId(request.getPaymentMethodId().toString());
        }

        // No coupon yet: it is valued once for the whole checkout (see checkout()) and each order's share is
        // put on by applyPromo() before the order is saved.
        BigDecimal promoDiscount = BigDecimal.ZERO;
        order.setPromoCode(null);

        BigDecimal deliveryFee = platformSettingsService.getDeliveryFeeAmount();
        BigDecimal platformFee = platformSettingsService.getPlatformFeeAmount();
        BigDecimal estimatedTax = platformFee; // platform fee shown as tax line item

        order.setDeliveryFee(deliveryFee);
        order.setEstimatedTax(estimatedTax);
        order.setPromoDiscount(promoDiscount);

        BigDecimal total = subtotal.add(deliveryFee).add(estimatedTax).subtract(promoDiscount);
        // This total is what the payment hold is created against. A negative
        // amount here is unrecoverable downstream (a refund-direction hold), so
        // it is floored at zero rather than trusted.
        order.setTotalAmount(total.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : total);

        return order;
    }

    /**
     * A customer-facing order number: {@code #DM-} plus
     * {@value #ORDER_NUMBER_RANDOM_CHARS} random base-36 characters.
     *
     * <p>This was two different bugs before it was settled.
     *
     * <p>First it was 6 random digits ({@code #DM-} + 100000..999999) against a
     * {@code unique = true} column with no collision handling. The draw was
     * atomic, which said nothing about uniqueness, so two concurrent checkouts
     * could pick the same number and the second INSERT would fail on the unique
     * constraint. Because checkout builds every order in one transaction, that
     * surfaced as a 500 on the whole multi-cart order rather than on the one
     * unlucky cart, and it got likelier as the table filled: with 10k orders in
     * 900k slots, a collision is roughly once per 90 order numbers.
     *
     * <p>It was then a base-36 timestamp plus a per-millisecond counter, which
     * fixed the collision rate but not the cause. The counter is per-process, so
     * two application instances ordering in the same millisecond still
     * overlapped, and it bought uniqueness with a silent guarantee that only
     * holds while a single node runs. Any clock that steps backwards can also
     * repeat a timestamp.
     *
     * <p>What is left draws purely from a CSPRNG. There is no shared state to
     * coordinate between instances and no clock to trust, so the number is
     * unique by entropy rather than by bookkeeping: 15 base-36 characters is
     * about 77 bits, putting the birthday bound near 10^11 orders. The cost is
     * that numbers no longer sort chronologically, so a support agent wanting
     * creation time should read {@code createdAt} rather than parse the number.
     */
    private String nextOrderNumber() {
        StringBuilder number = new StringBuilder(ORDER_NUMBER_PREFIX.length() + ORDER_NUMBER_RANDOM_CHARS);
        number.append(ORDER_NUMBER_PREFIX);
        for (int i = 0; i < ORDER_NUMBER_RANDOM_CHARS; i++) {
            number.append(BASE36_ALPHABET[ORDER_NUMBER_RANDOM.nextInt(BASE36_ALPHABET.length)]);
        }
        return number.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponseDto> getOrderHistory(UUID userId, Pageable pageable) {
        Page<Order> orders = orderRepository.findByUserId(userId, pageable);
        List<OrderResponseDto> dtoList = orders.getContent().stream()
                .map(orderResponseMapper::mapToDto)
                .collect(Collectors.toList());
        return new PageImpl<>(dtoList, pageable, orders.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponseDto> getOrderHistoryByStatusGroup(UUID userId, String statusGroup, Pageable pageable) {
        Page<Order> orders;
        if ("IN_PROGRESS".equalsIgnoreCase(statusGroup)) {
            List<OrderStatus> inProgress = List.of(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderStatus.PREPARING,
                    OrderStatus.OUT_FOR_DELIVERY);
            orders = orderRepository.findByUserIdAndStatusIn(userId, inProgress, pageable);
        } else if ("DELIVERED".equalsIgnoreCase(statusGroup)) {
            orders = orderRepository.findByUserIdAndStatus(userId, OrderStatus.DELIVERED, pageable);
        } else if ("CANCELLED".equalsIgnoreCase(statusGroup)) {
            orders = orderRepository.findByUserIdAndStatus(userId, OrderStatus.CANCELLED, pageable);
        } else {
            orders = orderRepository.findByUserId(userId, pageable);
        }

        List<OrderResponseDto> dtoList = orders.getContent().stream()
                .map(orderResponseMapper::mapToDto)
                .collect(Collectors.toList());
        return new PageImpl<>(dtoList, pageable, orders.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponseDto getOrderDetails(UUID userId, UUID orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));
        return orderResponseMapper.mapToDto(order);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderTrackingResponseDto trackOrder(UUID userId, UUID orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));

        List<StatusTimelineDto> timeline = new ArrayList<>();
        OrderStatus currentStatus = order.getStatus();

        timeline.add(StatusTimelineDto.builder()
                .step(1)
                .label("Placed")
                .completedAt(order.getCreatedAt())
                .isCurrent(currentStatus == OrderStatus.PLACED)
                .build());

        Instant preparingAt = order.getPreparingAt() != null ? order.getPreparingAt()
                : (currentStatus.ordinal() >= OrderStatus.CONFIRMED.ordinal()
                        ? order.getCreatedAt().plus(5, ChronoUnit.MINUTES)
                        : null);
        timeline.add(StatusTimelineDto.builder()
                .step(2)
                .label("Prepared")
                .completedAt(preparingAt)
                .isCurrent(currentStatus == OrderStatus.PREPARING || currentStatus == OrderStatus.CONFIRMED)
                .build());

        Instant outForDeliveryAt = order.getOutForDeliveryAt() != null ? order.getOutForDeliveryAt()
                : (currentStatus.ordinal() >= OrderStatus.OUT_FOR_DELIVERY.ordinal()
                        ? order.getCreatedAt().plus(15, ChronoUnit.MINUTES)
                        : null);
        timeline.add(StatusTimelineDto.builder()
                .step(3)
                .label("On the way")
                .completedAt(outForDeliveryAt)
                .isCurrent(currentStatus == OrderStatus.OUT_FOR_DELIVERY)
                .build());

        timeline.add(StatusTimelineDto.builder()
                .step(4)
                .label("Delivered")
                .completedAt(order.getDeliveredAt())
                .isCurrent(currentStatus == OrderStatus.DELIVERED)
                .build());

        CartVendorResolver.Session itemVendors = itemSession(order);
        List<OrderItemResponseDto> items = order.getItems().stream()
                .map(item -> {
                    ProductDto product = safeGetProduct(item.getProductId(), itemVendors);
                    String name = product != null ? product.getName() : "Unknown Product";
                    return OrderItemResponseDto.builder()
                            .id(item.getId())
                            .productId(item.getProductId())
                            .productName(name)
                            .quantity(item.getQuantity())
                            .price(item.getPrice())
                            .unit(item.getUnit())
                            .subTotal(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                            .build();
                })
                .collect(Collectors.toList());

        boolean rated = ratingRepository.findByOrderId(orderId).isPresent();

        if ((order.getStatus() == OrderStatus.OUT_FOR_DELIVERY || order.getStatus() == OrderStatus.DELIVERED)
                && (order.getDropOtp() == null || order.getDropOtp().isBlank())) {
            String autoOtp = String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
            order.setDropOtp(autoOtp);
            orderRepository.save(order);
        }

        // Vendor identity -- null until a shop has actually accepted this
        // order. FIXED THIS ROUND: deliveryAgentName/Phone below used to fall
        // back to a fake hardcoded agent ("John Veggie" / a made-up phone
        // number) whenever no real partner had been assigned yet -- that
        // fabricated a delivery person who doesn't exist. Now both stay null
        // until a real partner accepts, same as everywhere else.
        String shopName = null;
        String shopBusinessPhone = null;
        if (order.getAcceptedShopId() != null) {
            var shop = shopLookupService.findShopSummaryById(order.getAcceptedShopId()).orElse(null);
            if (shop != null) {
                shopName = shop.getName();
                shopBusinessPhone = shop.getBusinessPhone();
            }
        }

        return OrderTrackingResponseDto.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .status(order.getStatus().name())
                .deliveryAddress(order.getDeliveryAddress())
                .estimatedDeliveryWindow(
                        order.getEstimatedDeliveryWindow() != null ? order.getEstimatedDeliveryWindow() : "20-30 mins")
                .currentLatitude(order.getLatitude() + 0.001)
                .currentLongitude(order.getLongitude() - 0.001)
                .shopName(shopName)
                .shopBusinessPhone(shopBusinessPhone)
                .deliveryAgentName(order.getDeliveryAgentName())
                .deliveryAgentPhone(order.getDeliveryAgentPhone())
                .deliveryAgentPhotoUrl(order.getDeliveryAgentPhotoUrl())
                .statusTimeline(timeline)
                .items(items)
                .total(order.getTotalAmount())
                .deliveryPhotoUrl(order.getDeliveryPhotoUrl())
                .deliveryLocationNote(order.getDeliveryLocationNote())
                .deliveredAt(order.getDeliveredAt())
                .hasBeenRated(rated)
                .dropOtp(order.getDropOtp())
                .build();
    }

    @Override
    @Transactional
    public String getDropOtp(UUID userId, UUID orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));

        if ((order.getStatus() == OrderStatus.OUT_FOR_DELIVERY || order.getStatus() == OrderStatus.DELIVERED)
                && (order.getDropOtp() == null || order.getDropOtp().isBlank())) {
            String autoOtp = String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
            order.setDropOtp(autoOtp);
            orderRepository.save(order);
        }
        return order.getDropOtp();
    }

    @Override
    public RatingResponseDto rateOrder(UUID userId, UUID orderId, RatingRequestDto request) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));

        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException("ORDER_NOT_DELIVERED", "You can only rate orders that have been delivered",
                    HttpStatus.BAD_REQUEST);
        }

        ratingRepository.findByOrderId(orderId).ifPresent(r -> {
            throw new BusinessException("ORDER_ALREADY_RATED", "This order has already been rated",
                    HttpStatus.BAD_REQUEST);
        });

        // NOTE: unchanged this round — still only writes to Rating, does not
        // orchestrate VendorShopRating/DeliveryPartnerRating (PROJECT_STATE
        // "rating fragmentation" gap). Out of scope for Phase 1/2, deferred
        // per the agreed round scope. See NOTES_CUSTOMER.md.
        Rating rating = new Rating();
        rating.setOrderId(orderId);
        rating.setRatingValue(request.getRatingValue());
        rating.setComment(request.getComment());

        Rating saved = ratingRepository.save(rating);

        // REVIEW RECEIVED → the shop that actually fulfilled this order.
        UUID shopId = order.getAcceptedShopId();
        if (shopId != null) {
            shopLookupService.findOwnerUserIdByShopId(shopId).ifPresent(ownerId -> notificationService.send(ownerId,
                    NotificationRecipientRole.VENDOR, NotificationType.REVIEW_RECEIVED,
                    "New review received",
                    "A customer rated " + request.getRatingValue() + "/5 for order " + order.getOrderNumber(),
                    "{\"orderId\":\"" + orderId + "\",\"rating\":" + request.getRatingValue()
                            + ",\"comment\":"
                            + com.veggofresh.notification.util.NotificationJson.str(request.getComment()) + "}"));
        }

        return RatingResponseDto.builder()
                .id(saved.getId())
                .orderId(saved.getOrderId())
                .ratingValue(saved.getRatingValue())
                .comment(saved.getComment())
                .build();
    }

    @Override
    public OrderResponseDto updateOrderStatus(UUID orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));

        if (!order.getStatus().isValidTransition(newStatus)) {
            throw new BusinessException("INVALID_ORDER_STATE_TRANSITION",
                    "Cannot transition order from status " + order.getStatus() + " to " + newStatus,
                    HttpStatus.BAD_REQUEST);
        }

        order.setStatus(newStatus);

        if (newStatus == OrderStatus.CONFIRMED)
            order.setConfirmedAt(Instant.now());
        else if (newStatus == OrderStatus.PREPARING)
            order.setPreparingAt(Instant.now());
        else if (newStatus == OrderStatus.OUT_FOR_DELIVERY) {
            order.setOutForDeliveryAt(Instant.now());
            if (order.getDropOtp() == null || order.getDropOtp().isBlank()) {
                order.setDropOtp(String.format("%06d", new java.security.SecureRandom().nextInt(1000000)));
            }
        } else if (newStatus == OrderStatus.DELIVERED)
            order.setDeliveredAt(Instant.now());
        else if (newStatus == OrderStatus.CANCELLED) {
            order.setCancelledAt(Instant.now());
            // Its share of the coupon goes back (idempotent; no-op when the order had no coupon).
            couponService.onOrderCancelled(orderId);
        }

        Order saved = orderRepository.save(order);

        notifyCustomerOrderStatusChange(saved, newStatus);

        return orderResponseMapper.mapToDto(saved);
    }

    /**
     * Notify the customer on every forward order-status change. This is the ONE
     * funnel every status change passes through (vendor updates via
     * CustomerOrderService, delivery pickup/delivery completion, system cancel),
     * so hooking here guarantees a single, consistent customer notification per
     * transition with no double-fires from the various callers.
     */
    private void notifyCustomerOrderStatusChange(Order order, OrderStatus newStatus) {
        NotificationType type;
        String title;
        switch (newStatus) {
            case PREPARING -> {
                type = NotificationType.ORDER_PACKED;
                title = "Your order is being prepared";
            }
            case READY_FOR_PICKUP -> {
                type = NotificationType.ORDER_READY_FOR_PICKUP;
                title = "Your order is packed & ready";
            }
            case OUT_FOR_DELIVERY -> {
                type = NotificationType.ORDER_OUT_FOR_DELIVERY;
                title = "Your order is out for delivery";
            }
            case DELIVERED -> {
                type = NotificationType.ORDER_DELIVERED;
                title = "Your order has been delivered";
            }
            case CONFIRMED -> {
                type = NotificationType.ORDER_CONFIRMED;
                title = "Your order was confirmed";
            }
            case CANCELLED -> {
                type = NotificationType.ORDER_CANCELLED;
                title = "Your order was cancelled";
            }
            default -> {
                return; // PLACED — nothing to announce
            }
        }
        notificationService.send(order.getUserId(), NotificationRecipientRole.CUSTOMER, type, title,
                "Order " + order.getOrderNumber() + " — " + title.toLowerCase(Locale.ROOT), orderData(order));
    }

    /**
     * Order placed: ping the customer (ORDER_PLACED).
     * The candidate vendor broadcast (NEW_ORDER_REQUEST) is deferred to verifyPayment()
     * so that vendors are only notified after payment is verified and authorized.
     */
    private void notifyOrderPlaced(UUID customerUserId, Order saved) {
        notificationService.send(customerUserId, NotificationRecipientRole.CUSTOMER, NotificationType.ORDER_PLACED,
                "Order placed successfully",
                "Your order " + saved.getOrderNumber() + " has been placed — a nearby shop will confirm it shortly",
                orderData(saved));
    }

    private String orderData(Order order) {
        return "{\"orderId\":\"" + order.getId() + "\",\"orderNumber\":\"" + order.getOrderNumber() + "\"}";
    }

    /**
     * Cancels an order and hands the money question to the payment module.
     *
     * <p>The refund is NOT credited here. That comment used to sit on a
     * {@code walletService.credit(order.getTotalAmount(), ...)} call whose stated
     * rationale was that "real payment collection does not exist yet", so a refund
     * was booked unconditionally. Razorpay integration now does exist, and
     * {@link PaymentService#onOrderCancelled(UUID)} distinguishes an uncaptured
     * hold (void it, nothing to refund) from a captured one (refund it) using the
     * payment line as the source of truth. Booking the credit here as well
     * double-refunded every paid cancellation and paid out free wallet credit for
     * every unpaid one.
     */
    @Override
    public OrderResponseDto cancelOrder(UUID userId, UUID orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));

        if (order.getStatus() != OrderStatus.PLACED && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new BusinessException("ORDER_NOT_CANCELLABLE", "Can only cancel orders that are PLACED or CONFIRMED",
                    HttpStatus.BAD_REQUEST);
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        Order saved = orderRepository.save(order);

        notificationService.send(saved.getUserId(), NotificationRecipientRole.CUSTOMER,
                NotificationType.ORDER_CANCELLED,
                "Your order was cancelled",
                "Order " + saved.getOrderNumber() + " was cancelled — your refund is on the way",
                orderData(saved));

        // The refund belongs to the payment module and nowhere else.
        // PaymentService.onOrderCancelled() is the only party that knows whether
        // money was actually collected: it voids the line when the batch was
        // never captured (no money in, so nothing to give back) and credits a
        // refund only when it was.
        //
        // This method used to ALSO credit the order total unconditionally, before
        // calling onOrderCancelled(). Two consequences, both real money:
        //
        //   - a cancelled COD or wallet-paid order, where no money was ever
        //     captured, still credited the customer's wallet with the order
        //     total -- free money, repeatable per cancellation;
        //   - a cancelled online-paid order was credited twice, once here and
        //     once from the post-capture branch of onOrderCancelled(). Checkout
        //     fans one payment hold out over N orders, so cancelling all three
        //     of a 3-cart order returned six refunds for three orders.
        //
        // Note the order status is still saved as CANCELLED above, before the
        // payment module is consulted, so a webhook arriving for this order
        // afterwards still resolves against a cancelled order.
        paymentService.onOrderCancelled(orderId);

        return orderResponseMapper.mapToDto(saved);
    }

    @Override
    public List<CartResponseDto> reorder(UUID userId, UUID orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Original order not found",
                        HttpStatus.NOT_FOUND));

        if (order.getItems() == null || order.getItems().isEmpty()) {
            throw new BusinessException("ORDER_EMPTY", "This order has no items to reorder", HttpStatus.BAD_REQUEST);
        }

        // The whole reorder is one transaction, so a product that has since
        // become unbuyable rolls the entire re-add back rather than leaving the
        // customer with a half-populated cart that looks like a bug.
        for (OrderItem item : order.getItems()) {
            ProductDto product = productCatalogService.getProductById(item.getProductId(), order.getLatitude(),
                    order.getLongitude());
            if (product == null) {
                throw new BusinessException("PRODUCT_NOT_AVAILABLE",
                        "Some products from your previous order are no longer available", HttpStatus.BAD_REQUEST);
            }

            CartItemRequestDto req = new CartItemRequestDto();
            req.setProductId(item.getProductId());
            req.setQuantity(item.getQuantity());
            cartService.addItemToCart(userId, req);
        }

        // Previously this returned the result of the last addItemToCart call,
        // which is null for an order with no items — the client received a null
        // body inside a non-null ApiResponse wrapper.
        return cartService.getOpenCarts(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceDto getInvoice(UUID userId, UUID orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));

        UserSummaryDto user = userLookupService.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User profile not found in auth module"));

        // PHASE 1 FIX: use the customer's real display name — CustomerProfile
        // .fullName was already being stored via the profile endpoints but
        // never actually read here; this used to fall straight to phone.
        String customerName = customerProfileRepository.findByUserId(userId)
                .map(CustomerProfile::getFullName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(user.getPhone());

        CartVendorResolver.Session invoiceVendors = itemSession(order);
        List<InvoiceLineItemDto> lineItems = order.getItems().stream()
                .map(item -> {
                    ProductDto product = safeGetProduct(item.getProductId(), invoiceVendors);
                    String name = product != null ? product.getName() : "Unknown Product";
                    return InvoiceLineItemDto.builder()
                            .productName(name)
                            .quantity(item.getQuantity())
                            .unitPrice(item.getPrice())
                            .unit(item.getUnit())
                            .subTotal(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                            .build();
                })
                .collect(Collectors.toList());

        BigDecimal subtotal = order.getItems().stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Seller of record -- null until a shop has accepted this order.
        String shopName = null;
        String shopAddress = null;
        if (order.getAcceptedShopId() != null) {
            var shop = shopLookupService.findShopSummaryById(order.getAcceptedShopId()).orElse(null);
            if (shop != null) {
                shopName = shop.getName();
                shopAddress = shop.getAddress();
            }
        }

        return InvoiceDto.builder()
                .orderNumber(order.getOrderNumber())
                .orderDate(order.getCreatedAt().toString())
                .customerName(customerName)
                .customerEmail(user.getEmail())
                .customerPhone(user.getPhone())
                .shopName(shopName)
                .shopAddress(shopAddress)
                .deliveryAddress(order.getDeliveryAddress())
                .items(lineItems)
                .subtotal(subtotal)
                .deliveryFee(order.getDeliveryFee())
                .estimatedTax(order.getEstimatedTax())
                .promoDiscount(order.getPromoDiscount())
                .promoCode(order.getPromoCode())
                .total(order.getTotalAmount())
                .paymentMethod(displayPaymentMethod(order))
                .build();
    }

    /**
     * Renders the stored payment method for display.
     *
     * <p>This used to be {@code paymentMethodId != null ? "Credit Card" : "COD"},
     * which discarded whatever the customer actually chose at checkout. The field
     * holds a free-text label ({@code COD}, {@code UPI}, {@code ONLINE},
     * {@code WALLET}), so every prepaid order was reported to the customer and to
     * the vendor as a card payment, and UPI and wallet orders were indistinguishable
     * in support and reconciliation.
     *
     * <p>Falls back to COD when nothing was stored, which is what checkout does for
     * an order placed without a method.
     */
    private String displayPaymentMethod(Order order) {
        String stored = order.getPaymentMethodId();
        if (stored == null || stored.isBlank()) {
            return "COD";
        }
        String trimmed = stored.trim();
        return trimmed.isEmpty() ? "COD" : trimmed.toUpperCase(Locale.ROOT);
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutSummaryDto getCheckoutSummary(UUID userId, UUID addressId, List<UUID> cartIds, String couponCode) {
        Address address = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new BusinessException("ADDRESS_NOT_FOUND", "Invalid address selected",
                        HttpStatus.BAD_REQUEST));

        CartVendorResolver.Session vendors = newVendorSession(address);

        // The same visibility rule the cart screen applies, so the labels below
        // are the customer's real "Cart 1, Cart 2, ..." and not a second,
        // disagreeing numbering.
        List<Cart> carts = shoppableCarts(userId, vendors);
        if (carts.isEmpty()) {
            throw new BusinessException("CART_EMPTY", "You have no items in any cart", HttpStatus.BAD_REQUEST);
        }

        BigDecimal deliveryFee = platformSettingsService.getDeliveryFeeAmount();
        BigDecimal estimatedTax = platformSettingsService.getPlatformFeeAmount();

        List<CartCheckoutBreakdownDto> breakdowns = new ArrayList<>();
        List<CheckoutIssueDto> issues = new ArrayList<>();
        int totalItemCount = 0;
        BigDecimal grandTotal = BigDecimal.ZERO;
        List<SummaryLine> couponLines = new ArrayList<>();
        List<UUID> couponCartIds = new ArrayList<>();
        Map<UUID, BigDecimal> couponItemsByCart = new LinkedHashMap<>();

        // Optional filtering: summarise only the requested carts.
        // cartIds null/empty -> summarise every open cart (backward compatible).
        // Labels still come from the cart's real position, so previewing a single
        // cart shows "Cart 2" and not "Cart 1" — the preview must agree with
        // the cart screen and with checkout.
        final boolean onlyRequested = cartIds != null && !cartIds.isEmpty();
        if (onlyRequested && carts.stream().noneMatch(c -> cartIds.contains(c.getId()))) {
            throw new BusinessException("CART_NOT_FOUND",
                    "None of the specified cart IDs were found in your open carts",
                    HttpStatus.BAD_REQUEST);
        }

        for (int i = 0; i < carts.size(); i++) {
            Cart cart = carts.get(i);
            String cartLabel = CartServiceImpl.cartLabel(i);

            if (onlyRequested && !cartIds.contains(cart.getId())) {
                continue;
            }

            // Same two exclusions checkout() applies, evaluated up front so the
            // summary shows what will ACTUALLY be charged. A summary that
            // includes a cart checkout will then reject is worse than no
            // summary: the customer confirms a total that is wrong on submit.
            if (!vendors.revalidate(cart)) {
                issues.add(issue(cart, cartLabel,
                        "Some items in this group are no longer available together — this group will be left out of your order"));
                continue;
            }
            List<CartItem> shippable = vendors.onlyShippableItems(cart);
            if (shippable.isEmpty()) {
                issues.add(issue(cart, cartLabel,
                        "None of the items in this group are available for delivery to this address — this group will be left out of your order"));
                continue;
            }

            BigDecimal subtotal = BigDecimal.ZERO;
            int itemCount = 0;
            int unavailableItemCount = 0;
            for (CartItem item : cart.getItems()) {
                ProductDto product = safeGetProduct(item.getProductId(), vendors);
                if (product == null || product.getPrice() == null) {
                    unavailableItemCount++;
                    continue;
                }
                // Sum of QUANTITIES, not the number of line items. This is the
                // value the cart screen and the badge already report; the
                // mismatch is exactly what made a two-cart customer see one
                // number on the cart page and a different one at checkout.
                itemCount += item.getQuantity();
                subtotal = subtotal.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                couponLines.add(new SummaryLine(cart.getId(), item.getProductId(), product.getPrice(), item.getQuantity()));
            }
            couponCartIds.add(cart.getId());
            couponItemsByCart.put(cart.getId(), subtotal);

            // The coupon is valued once for all the carts together, after this loop; until then no discount.
            BigDecimal promoDiscount = BigDecimal.ZERO;
            BigDecimal total = subtotal.add(deliveryFee).add(estimatedTax);

            breakdowns.add(CartCheckoutBreakdownDto.builder()
                    .cartId(cart.getId())
                    .cartLabel(cartLabel)
                    .itemCount(itemCount)
                    .unavailableItemCount(unavailableItemCount)
                    .subtotal(subtotal)
                    .deliveryFee(deliveryFee)
                    .estimatedTax(estimatedTax)
                    .promoDiscount(promoDiscount)
                    .promoCode(null)
                    .total(total)
                    .build());

            totalItemCount += itemCount;
            grandTotal = grandTotal.add(total);
        }

        if (breakdowns.isEmpty()) {
            // Distinguish "you asked for carts that aren't here" from "the carts
            // you have can't ship here" — the first is a client error, the second
            // is a catalog/address problem, and they need different fixes.
            if (onlyRequested) {
                throw new BusinessException("CART_NOT_FOUND",
                        "None of the specified cart IDs were found in your open carts",
                        HttpStatus.BAD_REQUEST);
            }
            throw new BusinessException("CART_EMPTY",
                    "None of your carts can be delivered to this address right now", HttpStatus.BAD_REQUEST);
        }

        // ── One coupon for exactly the carts being paid here ──
        Map<UUID, BigDecimal> vendorPrices = couponLines.isEmpty() ? Map.of()
                : adminProductService.getVendorPrices(
                        couponLines.stream().map(SummaryLine::productId).collect(Collectors.toSet()));
        Map<UUID, BigDecimal> marginByCart = new LinkedHashMap<>();
        for (SummaryLine line : couponLines) {
            BigDecimal vendorPrice = vendorPrices.get(line.productId());
            if (vendorPrice != null && vendorPrice.compareTo(line.price()) < 0) {
                marginByCart.merge(line.cartId(),
                        line.price().subtract(vendorPrice).multiply(BigDecimal.valueOf(line.quantity())),
                        BigDecimal::add);
            }
        }
        List<CouponMath.Basis> bases = new ArrayList<>(couponCartIds.size());
        for (UUID cartId : couponCartIds) {
            bases.add(new CouponMath.Basis(cartId, couponItemsByCart.get(cartId),
                    marginByCart.getOrDefault(cartId, BigDecimal.ZERO)));
        }
        CouponQuote quote = couponService.quote(userId, couponCode, bases);

        grandTotal = BigDecimal.ZERO;
        for (CartCheckoutBreakdownDto b : breakdowns) {
            BigDecimal share = quote.shareOf(b.getCartId());
            BigDecimal total = b.getSubtotal().add(b.getDeliveryFee()).add(b.getEstimatedTax()).subtract(share);
            if (total.compareTo(BigDecimal.ZERO) < 0) {
                total = BigDecimal.ZERO;
            }
            b.setPromoDiscount(share);
            b.setPromoCode(share.signum() > 0 ? quote.code() : null);
            b.setTotal(total);
            grandTotal = grandTotal.add(total);
        }

        return CheckoutSummaryDto.builder()
                .carts(breakdowns)
                .totalItemCount(totalItemCount)
                .grandTotal(grandTotal)
                .issues(issues)
                .promoCode(quote.status() == CouponQuote.Status.APPLIED ? quote.code() : null)
                .promoDiscount(quote.total())
                .promoStatus(quote.status().name())
                .promoMessage(quote.message())
                .build();
    }

    /**
     * Resolves a product exactly the way the cart screen did, through the same
     * cached session lookup. Going straight to the catalog here is what let the
     * availability guard and the price disagree: two calls, two answers.
     *
     * <p>Vendor's getProductById throws rather than returning null on
     * not-found/not-eligible (always has, before and after the catalog pivot).
     * Wrapping it restores the graceful per-item skip the `if (product != null)`
     * checks in this class visually intended.
     *
     * <p>Uses the non-throwing findEligibleProductById with NO try/catch on
     * purpose. Swallowing an exception raised inside a nested @Transactional
     * method still marks this transaction rollback-only, and the failure only
     * surfaces at commit as UnexpectedRollbackException -- long after the catch
     * block, with no clue which item caused it.
     */
    private ProductDto safeGetProduct(UUID productId, CartVendorResolver.Session vendors) {
        return vendors.productFor(productId);
    }

    /** One cart line of the checkout summary, kept so the margin can be worked out after the loop. */
    private record SummaryLine(UUID cartId, UUID productId, BigDecimal price, int quantity) {
    }

    /**
     * A session pinned to the order's own delivery location, for reading back the
     * products on an order that already exists. Vendor eligibility is
     * location-dependent, so this deliberately reuses the order's coordinates
     * rather than the customer's current position.
     */
    private CartVendorResolver.Session itemSession(Order order) {
        // Primitives, so no null check is possible: an order with no stored
        // coordinates falls back to the origin rather than throwing mid-render.
        return cartVendorResolver.newSession(order.getLatitude(), order.getLongitude());
    }
}
