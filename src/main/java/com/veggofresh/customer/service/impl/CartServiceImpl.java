package com.veggofresh.customer.service.impl;

import com.veggofresh.admin.service.AdminProductService;
import com.veggofresh.admin.service.PlatformSettingsService;
import com.veggofresh.coupon.dto.CouponPreview;
import com.veggofresh.coupon.service.CouponCheckoutService;
import com.veggofresh.coupon.service.CouponMath;
import com.veggofresh.customer.dto.request.CartItemRequestDto;
import com.veggofresh.customer.dto.response.CartItemResponseDto;
import com.veggofresh.customer.dto.response.CartResponseDto;
import com.veggofresh.customer.entity.Address;
import com.veggofresh.customer.entity.Cart;
import com.veggofresh.customer.entity.CartItem;
import com.veggofresh.customer.repository.AddressRepository;
import com.veggofresh.customer.repository.CartItemRepository;
import com.veggofresh.customer.repository.CartRepository;
import com.veggofresh.customer.service.CartService;
import com.veggofresh.customer.service.CartVendorResolver;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.dto.ProductDto;
import com.veggofresh.vendor.service.ProductCatalogService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PHASE 2 — NEW ARCHITECTURE, multi-cart model (PROJECT_STATE section 2).
 *
 * VENDOR CATALOG PIVOT PATCH: ProductCatalogService methods now require a
 * latitude/longitude (radius eligibility depends on where the customer is).
 * Since add-to-cart/browsing happens before any checkout address is chosen,
 * this class resolves location from the customer's DEFAULT saved Address
 * (falling back to their first address if none is marked default) via
 * resolveLocation() below. If the customer has no address at all yet, cart
 * operations now require adding one first (ADDRESS_REQUIRED) -- a real
 * behavior change, flagged in NOTES_CUSTOMER.md, not silently introduced.
 *
 * <h2>Invariants this class guarantees</h2>
 *
 * <ol>
 *   <li><b>No ghost carts.</b> An open cart that has lost its last item is
 *       soft-deleted, never rendered, and never counted. Previously removal left
 *       the row open, so a customer with two real carts saw three "Cart N"
 *       cards, the badge and the checkout summary disagreed with the cart list,
 *       and the labels the client uses to line up a checkout breakdown with a
 *       cart no longer matched. See {@link #retireIfEmptied}.</li>
 *   <li><b>Counts mean one thing.</b> {@code itemCount} is always the sum of
 *       line-item quantities, everywhere, matching the badge. Counting distinct
 *       line items instead made a cart of 2 lines x qty 2+5 read as 2 on the
 *       checkout summary and 7 on the cart screen.</li>
 *   <li><b>Counts only describe what is payable.</b> Line items whose product is
 *       no longer available are excluded from both the totals and the count, so
 *       the two can never disagree. They are reported separately as
 *       {@code unavailableItemCount} rather than silently dropped.</li>
 *   <li><b>No zero/negative quantities.</b> Guarded at the boundary, since a
 *       negative quantity would produce a negative subtotal and a negative
 *       amount to charge.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final AddressRepository addressRepository;
    private final ProductCatalogService productCatalogService;
    private final CouponCheckoutService couponService;
    private final AdminProductService adminProductService;
    private final PlatformSettingsService platformSettingsService;
    private final CartVendorResolver cartVendorResolver;

    @Override
    @Transactional(readOnly = true)
    public List<CartResponseDto> getOpenCarts(UUID userId) {
        return toResponses(userId);
    }

    @Override
    public List<CartResponseDto> addItemToCart(UUID userId, CartItemRequestDto request) {
        int quantity = requirePositiveQuantity(request.getQuantity(), "Quantity must be at least 1");

        double[] location = resolveLocation(userId);
        CartVendorResolver.Session vendors = cartVendorResolver.newSession(location[0], location[1]);

        // getProductById is the correct call here: adding something the customer
        // cannot buy is a genuine error, so it should surface loudly rather than
        // be skipped. It is safe because the exception is NOT caught (see the
        // rollback-only note on safeGetProduct).
        productCatalogService.getProductById(request.getProductId(), location[0], location[1]);

        Set<UUID> productVendorIds = vendors.vendorsFor(request.getProductId());
        if (productVendorIds.isEmpty()) {
            throw new BusinessException("PRODUCT_NOT_AVAILABLE", "This product currently has no vendor carrying it",
                    HttpStatus.BAD_REQUEST);
        }

        // Load every open cart, retire any that has been emptied, and only then
        // consider the survivors. The order matters: filtering first would hand
        // retireGhostCarts a list with no ghosts in it, so the cleanup would
        // silently do nothing and the dead rows would accumulate.
        List<Cart> allOpenCarts = cartRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId);
        List<Cart> openCarts = retireGhostCarts(allOpenCarts);

        // Best-fit, not first-fit: the cart that keeps the most vendor overlap
        // survives. See CartVendorResolver#bestCartFor for why the old greedy
        // first-match narrowed carts into over-splitting.
        Cart targetCart = vendors.bestCartFor(openCarts, request.getProductId()).orElse(null);

        if (targetCart == null) {
            targetCart = new Cart();
            targetCart.setUserId(userId);
            targetCart.setCandidateVendorIds(new HashSet<>(productVendorIds));
            targetCart = cartRepository.save(targetCart);
        }

        Optional<CartItem> existingItem = targetCart.getItems().stream()
                .filter(item -> item.getProductId().equals(request.getProductId()))
                .findFirst();

        if (existingItem.isPresent()) {
            CartItem item = existingItem.get();
            item.setQuantity(item.getQuantity() + quantity);
            cartItemRepository.save(item);
        } else {
            CartItem newItem = new CartItem();
            newItem.setCart(targetCart);
            newItem.setProductId(request.getProductId());
            newItem.setQuantity(quantity);
            targetCart.getItems().add(newItem);
            cartItemRepository.save(newItem);
        }

        // Re-derive the stored vendor set from the items as they now stand,
        // rather than narrowing whatever was there before. The same session is
        // reused for the promo recheck below, so this add costs one pass of
        // vendor lookups, not two.
        vendors.resync(targetCart);
        cartRepository.save(targetCart);

        return toResponses(userId, vendors);
    }

    @Override
    public List<CartResponseDto> updateCartItem(UUID userId, UUID cartItemId, int quantity) {
        // Quantity 0 (or below) means "remove this line". Preserved from
        // 51cf525: a client decrementing a line to zero expects the line to go
        // away, and a stored zero-quantity row would otherwise sit in the cart
        // forever — priced at nothing and impossible to check out. Delegating
        // to removeCartItem also means the emptied-cart retirement and the
        // vendor re-sync below happen through one path, not two.
        if (quantity <= 0) {
            return removeCartItem(userId, cartItemId);
        }

        CartItem item = cartItemRepository.findByIdAndCart_UserId(cartItemId, userId)
                .orElseThrow(() -> new BusinessException("CART_ITEM_NOT_FOUND", "Item not found in your cart",
                        HttpStatus.NOT_FOUND));

        item.setQuantity(quantity);
        cartItemRepository.save(item);

        Cart cart = item.getCart();
        CartVendorResolver.Session vendors = newSessionFor(cart.getUserId());
        vendors.resync(cart);
        cartRepository.save(cart);

        return toResponses(userId, vendors);
    }

    @Override
    public List<CartResponseDto> removeCartItem(UUID userId, UUID cartItemId) {
        CartItem item = cartItemRepository.findByIdAndCart_UserId(cartItemId, userId)
                .orElseThrow(() -> new BusinessException("CART_ITEM_NOT_FOUND", "Item not found in your cart",
                        HttpStatus.NOT_FOUND));

        Cart cart = item.getCart();
        cart.getItems().remove(item);
        cartItemRepository.delete(item);

        // The cart's vendor overlap IS re-derived here. The previous version
        // deliberately left it stale on removal ("carts are static once formed"),
        // which permanently over-restricted a cart whose removed line had been
        // the only thing holding a vendor in the intersection — the next add
        // would then open a needless extra cart. Re-deriving can only widen the
        // set, so it can never make cart selection worse, and it keeps the
        // stored set equal to the live truth (Cart's class javadoc).
        //
        // retireIfEmptied() below still closes the cart when the last line goes,
        // so a ghost is never left open.
        CartVendorResolver.Session vendors = newSessionFor(cart.getUserId());
        vendors.resync(cart);
        cartRepository.save(cart);

        retireIfEmptied(cart);

        return toResponses(userId, vendors);
    }

    @Override
    public void clearCart(UUID userId, UUID cartId) {
        cartRepository.findByIdAndUserId(cartId, userId).ifPresent(cart -> {
            cart.softDelete();
            cartRepository.save(cart);
        });
    }

    @Override
    public void clearAllCarts(UUID userId) {
        cartRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId).forEach(cart -> {
            cart.softDelete();
            cartRepository.save(cart);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public int getCartCount(UUID userId) {
        return cartRepository.sumItemQuantities(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductDto> getCartRecommendations(UUID userId) {
        double[] location = resolveLocation(userId);
        List<Cart> carts = cartRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId);
        List<CartItem> allItems = carts.stream()
                .filter(Cart::hasItems)
                .flatMap(c -> c.getItems().stream())
                .collect(Collectors.toList());

        if (allItems.isEmpty()) {
            return productCatalogService.getDailyDeals(location[0], location[1]);
        }

        // Deduplicate by product: a customer with the same item in two carts
        // should not pay for the same "pairs well with" fan-out twice.
        Set<UUID> seen = new HashSet<>();
        List<ProductDto> recommendations = new ArrayList<>();
        for (CartItem item : allItems) {
            if (!seen.add(item.getProductId())) {
                continue;
            }
            // No try/catch. getRelatedProducts throws only when the product row
            // is genuinely gone, which is a real error worth surfacing --
            // swallowing it would mark this transaction rollback-only and
            // resurface as UnexpectedRollbackException at commit.
            List<ProductDto> related = productCatalogService.getRelatedProducts(item.getProductId(), location[0],
                    location[1]);
            if (related == null) {
                continue;
            }
            for (ProductDto p : related) {
                if (recommendations.size() >= RECOMMENDATION_LIMIT) {
                    return recommendations;
                }
                if (p != null && recommendations.stream().noneMatch(rec -> rec.getId().equals(p.getId()))) {
                    recommendations.add(p);
                }
            }
        }
        return recommendations;
    }

    @Override
    public List<CartResponseDto> applyPromoCode(UUID userId, UUID cartId, String code) {
        Cart cart = requireOwnedCart(cartId, userId);
        if (!cart.hasItems()) {
            throw new BusinessException("CART_EMPTY", "This cart has no items to discount", HttpStatus.BAD_REQUEST);
        }

        // The coupon belongs to the customer, not to this cart (the cart id only proves the caller owns a cart).
        // Only the rules that do not depend on the amount are checked here; what it is worth is decided at
        // checkout, from the carts actually being paid. A specific BusinessException is thrown when it can never
        // work for this customer (unknown, off, expired, limit reached, not for them).
        couponService.select(userId, code);

        return toResponses(userId);
    }

    @Override
    public List<CartResponseDto> removePromoCode(UUID userId, UUID cartId) {
        requireOwnedCart(cartId, userId);
        couponService.clearSelection(userId);
        return toResponses(userId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private static final int RECOMMENDATION_LIMIT = 6;

    /**
     * Maps every shoppable cart to a response, numbered "Cart 1..N" oldest
     * first.
     *
     * <p>The numbering is computed from the SAME filtered list that OrderService
     * uses for the checkout summary and checkout issues. It used to be an
     * independent running counter that also advanced past skipped empty carts,
     * so a leftover empty cart renumbered every label after it and the client
     * lined a checkout breakdown up against the wrong cart. One list, one
     * counter, one order — see CartService#cartLabel.
     */
    private List<CartResponseDto> toResponses(UUID userId) {
        return toResponses(userId, newSessionFor(userId));
    }

    /**
     * Same, but reusing a session the caller already opened.
     *
     * <p>Mutating methods are handed a session to place the item and re-sync the
     * vendor set, and then have to render the resulting cart list. Without this
     * overload they would open a SECOND session, whose cache is cold, and
     * re-resolve the vendors of every product they had just looked up.
     */
    private List<CartResponseDto> toResponses(UUID userId, CartVendorResolver.Session vendors) {
        List<Cart> carts = vendors.visibleCarts(cartRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId));

        // Fee configuration and per-product lookups are identical for every cart
        // in this response. Resolving them once turns the previous per-cart,
        // per-item repeat queries into one of each.
        BigDecimal deliveryFee = platformSettingsService.getDeliveryFeeAmount();
        BigDecimal estimatedTax = platformSettingsService.getPlatformFeeAmount();
        Map<UUID, ProductDto> products = resolveProducts(carts, vendors);
        CouponPreview preview = previewCoupon(userId, carts, products);

        List<CartResponseDto> responses = new ArrayList<>(carts.size());
        for (Cart cart : carts) {
            responses.add(mapToDto(cart, cartLabel(responses.size()), products, deliveryFee, estimatedTax, preview));
        }
        return responses;
    }

    /**
     * The customer's display label for a cart, by position among carts that
     * still hold items. Public so OrderService labels its breakdowns, its
     * issues and its created orders identically instead of maintaining a third
     * copy of this counter.
     */
    public static String cartLabel(int position) {
        return "Cart " + (position + 1);
    }

    private CartResponseDto mapToDto(Cart cart, String label, Map<UUID, ProductDto> products,
            BigDecimal deliveryFee, BigDecimal estimatedTax, CouponPreview preview) {
        BigDecimal total = BigDecimal.ZERO;
        int itemCount = 0;
        int unavailableItemCount = 0;
        List<CartItemResponseDto> itemsList = new ArrayList<>();

        for (CartItem item : cart.getItems()) {
            ProductDto product = products.get(item.getProductId());
            if (product == null || product.getPrice() == null) {
                // Not buyable right now. Left in the cart (the customer decides
                // to remove it) but excluded from BOTH the money and the count,
                // so itemCount always describes exactly what is being charged.
                unavailableItemCount++;
                continue;
            }
            BigDecimal subTotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            total = total.add(subTotal);
            itemCount += item.getQuantity();

            itemsList.add(CartItemResponseDto.builder()
                    .id(item.getId())
                    .productId(item.getProductId())
                    .productName(product.getName())
                    .unitPrice(product.getPrice())
                    .unit(product.getUnit())
                    .quantity(item.getQuantity())
                    .subTotal(subTotal)
                    .productImageUrl(product.getImageUrl())
                    .build());
        }

        // What the coupon the customer is holding gives THIS cart if it were checked out alone. The checkout summary
        // has the final amounts for the carts actually paid together.
        BigDecimal promoDiscount = preview.discountOf(cart.getId());
        BigDecimal payable = total.add(deliveryFee).add(estimatedTax).subtract(promoDiscount);
        if (payable.compareTo(BigDecimal.ZERO) < 0) {
            payable = BigDecimal.ZERO;
        }

        return CartResponseDto.builder()
                .id(cart.getId())
                .userId(cart.getUserId())
                .cartLabel(label)
                .items(itemsList)
                .totalAmount(total)
                .itemCount(itemCount)
                .deliveryFee(deliveryFee)
                .estimatedTax(estimatedTax)
                .payableAmount(payable)
                .unavailableItemCount(unavailableItemCount)
                .promoCode(preview.code())
                .promoDiscount(promoDiscount)
                .promoMessage(preview.messageOf(cart.getId()))
                .build();
    }

    /**
     * Soft-deletes any open-but-empty cart, and returns the carts that still
     * hold something. This is the fix for the reported symptom: removing the
     * last item from a cart used to leave the row open, so a customer with two
     * real carts was shown a third, and that phantom then got re-labelled,
     * re-counted inconsistently, and could be resurrected by a later add
     * together with its stale promo code.
     *
     * <p>Write paths pass the UNFILTERED list; read paths go through
     * {@link CartVendorResolver.Session#visibleCarts} instead, which also drops
     * carts that hold only unbuyable products and needs no writes.
     */
    private List<Cart> retireGhostCarts(List<Cart> carts) {
        List<Cart> survivors = new ArrayList<>(carts.size());
        for (Cart cart : carts) {
            if (cart.hasItems()) {
                survivors.add(cart);
                continue;
            }
            cart.softDelete();
            cartRepository.save(cart);
            log.info("Retired now-empty cart {} — it held no items", cart.getId());
        }
        return survivors;
    }

    /** Retires a cart that has just lost its final item. */
    private void retireIfEmptied(Cart cart) {
        if (cart.hasItems()) {
            return;
        }
        cart.softDelete();
        cartRepository.save(cart);
        log.info("Retired now-empty cart {} — it held no items after the last removal", cart.getId());
    }

    private Cart requireOwnedCart(UUID cartId, UUID userId) {
        return cartRepository.findByIdAndUserId(cartId, userId)
                .orElseThrow(() -> new BusinessException("CART_NOT_FOUND", "Cart not found", HttpStatus.NOT_FOUND));
    }

    private int requirePositiveQuantity(int quantity, String message) {
        if (quantity < 1) {
            throw new BusinessException("INVALID_QUANTITY", message, HttpStatus.BAD_REQUEST);
        }
        return quantity;
    }

    private CartVendorResolver.Session newSessionFor(UUID userId) {
        double[] location = resolveLocation(userId);
        return cartVendorResolver.newSession(location[0], location[1]);
    }

    /** One eligible-product lookup per distinct product across the given carts. */
    private Map<UUID, ProductDto> resolveProducts(List<Cart> carts, CartVendorResolver.Session vendors) {
        Map<UUID, ProductDto> products = new HashMap<>();
        for (Cart cart : carts) {
            for (CartItem item : cart.getItems()) {
                if (products.containsKey(item.getProductId())) {
                    continue;
                }
                ProductDto product = safeGetProduct(item.getProductId(), vendors);
                if (product != null && product.getPrice() != null) {
                    products.put(item.getProductId(), product);
                }
            }
        }
        return products;
    }

    /**
     * What the coupon the customer is holding would give each cart if that cart were paid alone. Skips all the
     * pricing work (one cheap query) when the customer holds no coupon, which is the usual case.
     */
    private CouponPreview previewCoupon(UUID userId, List<Cart> carts, Map<UUID, ProductDto> products) {
        if (carts.isEmpty() || !couponService.holdsCoupon(userId)) {
            return CouponPreview.none();
        }
        java.util.Set<UUID> productIds = new java.util.HashSet<>();
        for (Cart cart : carts) {
            for (CartItem item : cart.getItems()) {
                if (products.containsKey(item.getProductId())) {
                    productIds.add(item.getProductId());
                }
            }
        }
        Map<UUID, BigDecimal> vendorPrices = adminProductService.getVendorPrices(productIds);

        List<CouponMath.Basis> bases = new ArrayList<>(carts.size());
        for (Cart cart : carts) {
            BigDecimal items = BigDecimal.ZERO;
            BigDecimal margin = BigDecimal.ZERO;
            for (CartItem item : cart.getItems()) {
                ProductDto product = products.get(item.getProductId());
                if (product == null) {
                    continue;
                }
                BigDecimal qty = BigDecimal.valueOf(item.getQuantity());
                items = items.add(product.getPrice().multiply(qty));
                BigDecimal vendorPrice = vendorPrices.get(item.getProductId());
                if (vendorPrice != null && vendorPrice.compareTo(product.getPrice()) < 0) {
                    margin = margin.add(product.getPrice().subtract(vendorPrice).multiply(qty));
                }
            }
            bases.add(new CouponMath.Basis(cart.getId(), items, margin));
        }
        return couponService.previewEach(userId, bases);
    }

    /**
     * VENDOR CATALOG PIVOT PATCH: resolves a reference location for radius
     * eligibility checks -- the customer's default saved Address, falling
     * back to their first address if none is marked default. Throws if they
     * have no address at all yet, since eligibility genuinely can't be
     * computed without one. This is a minimal compile-fix decision, not a
     * final design call -- see NOTES_CUSTOMER.md.
     */
    private double[] resolveLocation(UUID userId) {
        List<Address> addresses = addressRepository.findByUserId(userId);
        Address reference = addresses.stream()
                .filter(Address::isDefault)
                .findFirst()
                .orElse(addresses.isEmpty() ? null : addresses.get(0));

        if (reference == null) {
            throw new BusinessException("ADDRESS_REQUIRED",
                    "Add a delivery address before browsing or adding items to your cart", HttpStatus.BAD_REQUEST);
        }
        return new double[] { reference.getLatitude(), reference.getLongitude() };
    }

    /**
     * Resolves one product for response mapping, or null when it is no longer
     * available to this customer (removed, deactivated, or no vendor carrying it
     * in range). Callers skip nulls, so one unavailable product doesn't break a
     * whole cart/wishlist/order response.
     *
     * <p>⚠️ Deliberately has NO try/catch. It uses the non-throwing
     * {@code findEligibleProductById}, because a swallowed exception from a
     * nested {@code @Transactional} method still marks the caller's shared
     * transaction rollback-only and resurfaces as
     * {@code UnexpectedRollbackException} at commit. Never reintroduce a
     * catch-and-return-null here.
     */
    private ProductDto safeGetProduct(UUID productId, CartVendorResolver.Session vendors) {
        return vendors.productFor(productId);
    }
}
