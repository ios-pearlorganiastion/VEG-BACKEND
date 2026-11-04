package com.veggofresh.customer.service;

import com.veggofresh.admin.service.AdminProductService;
import com.veggofresh.coupon.dto.CouponQuote;
import com.veggofresh.coupon.service.CouponCheckoutService;
import com.veggofresh.coupon.service.CouponMath;
import com.veggofresh.customer.dto.response.CartPromoPreviewDto;
import com.veggofresh.customer.entity.Address;
import com.veggofresh.customer.entity.Cart;
import com.veggofresh.customer.entity.CartItem;
import com.veggofresh.customer.repository.AddressRepository;
import com.veggofresh.customer.repository.CartItemRepository;
import com.veggofresh.customer.repository.CartRepository;
import com.veggofresh.platform.exception.BusinessException;
import com.veggofresh.vendor.dto.ProductDto;
import com.veggofresh.vendor.service.ProductCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The combined coupon preview for the cart screen ({@code GET /api/customer/carts/promo-preview}).
 *
 * <p>The cart list values the coupon for each cart ALONE. This preview values the carts paid TOGETHER, through
 * the same read-only {@code quote} the checkout summary uses. These tests check that the right carts, prices and
 * margins are handed to that valuation, and that its answer is passed back unchanged.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartPromoPreviewTest {

    private static final double LAT = 12.9716;
    private static final double LNG = 77.5946;

    @Mock
    private CartRepository cartRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private ProductCatalogService productCatalogService;
    @Mock
    private CouponCheckoutService couponService;
    @Mock
    private AdminProductService adminProductService;

    private CartService cartService;
    private List<Cart> openCarts;

    private final UUID userId = UUID.randomUUID();
    private final UUID vendor1 = UUID.randomUUID();
    private final UUID couponId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        cartService = instantiate();
        openCarts = new ArrayList<>();

        Address address = new Address();
        address.setId(UUID.randomUUID());
        address.setUserId(userId);
        address.setLatitude(LAT);
        address.setLongitude(LNG);
        address.setDefault(true);
        when(addressRepository.findByUserId(userId)).thenReturn(List.of(address));
        when(cartRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId)).thenAnswer(i -> new ArrayList<>(openCarts));
        when(adminProductService.getVendorPrices(any())).thenReturn(Map.of());
        when(couponService.quote(eq(userId), any(), any())).thenReturn(CouponQuote.none());
    }

    private CartService instantiate() throws Exception {
        Constructor<?> constructor = com.veggofresh.customer.service.impl.CartServiceImpl.class
                .getDeclaredConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == CartRepository.class) {
                args[i] = cartRepository;
            } else if (types[i] == CartItemRepository.class) {
                args[i] = cartItemRepository;
            } else if (types[i] == AddressRepository.class) {
                args[i] = addressRepository;
            } else if (types[i] == ProductCatalogService.class) {
                args[i] = productCatalogService;
            } else if (types[i] == CouponCheckoutService.class) {
                args[i] = couponService;
            } else if (types[i] == AdminProductService.class) {
                args[i] = adminProductService;
            } else if (types[i] == CartVendorResolver.class) {
                args[i] = new CartVendorResolver(productCatalogService);
            }
        }
        constructor.setAccessible(true);
        return (CartService) constructor.newInstance(args);
    }

    private void stubProduct(UUID productId, BigDecimal price) {
        lenient().when(productCatalogService.getShopIdsForProduct(productId, LAT, LNG)).thenReturn(Set.of(vendor1));
        lenient().when(productCatalogService.findEligibleProductById(productId, LAT, LNG))
                .thenReturn(Optional.of(ProductDto.builder().id(productId).name("P").price(price).unit("kg").build()));
    }

    private Cart givenCart(UUID productId, int quantity) {
        Cart cart = new Cart();
        cart.setId(UUID.randomUUID());
        cart.setUserId(userId);
        CartItem item = new CartItem();
        item.setId(UUID.randomUUID());
        item.setCart(cart);
        item.setProductId(productId);
        item.setQuantity(quantity);
        cart.getItems().add(item);
        openCarts.add(cart);
        return cart;
    }

    @SuppressWarnings("unchecked")
    private List<CouponMath.Basis> capturedBases(String expectedCode) {
        ArgumentCaptor<List<CouponMath.Basis>> captor = ArgumentCaptor.forClass(List.class);
        verify(couponService).quote(eq(userId), eq(expectedCode), captor.capture());
        return captor.getValue();
    }

    // ── the case that was wrong on the cart screen ──────────────────────────

    @Test
    @DisplayName("Carts of 60 and 150 are valued TOGETHER: one discount for the bag")
    void valuesAllCartsTogether() {
        UUID lemons = UUID.randomUUID();
        UUID grapes = UUID.randomUUID();
        stubProduct(lemons, new BigDecimal("60"));
        stubProduct(grapes, new BigDecimal("50"));
        // Lemons carry a margin of 10; grapes have no vendor price at all (the "no margin" cart in the screenshots).
        when(adminProductService.getVendorPrices(any())).thenReturn(Map.of(lemons, new BigDecimal("50")));
        Cart cart1 = givenCart(lemons, 1);
        Cart cart2 = givenCart(grapes, 3);

        Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
        shares.put(cart1.getId(), new BigDecimal("30.00"));
        shares.put(cart2.getId(), new BigDecimal("0.00"));
        when(couponService.quote(eq(userId), any(), any())).thenReturn(
                CouponQuote.applied(couponId, "WELCOME30", null, new BigDecimal("30.00"), shares));

        CartPromoPreviewDto preview = cartService.previewPromo(userId, null, null);

        List<CouponMath.Basis> bases = capturedBases(null);
        assertEquals(2, bases.size(), "both carts go into ONE valuation");
        assertEquals(0, new BigDecimal("60").compareTo(bases.get(0).items()));
        assertEquals(0, new BigDecimal("10").compareTo(bases.get(0).margin()));
        assertEquals(0, new BigDecimal("150").compareTo(bases.get(1).items()));
        assertEquals(0, BigDecimal.ZERO.compareTo(bases.get(1).margin()));

        assertEquals("APPLIED", preview.getStatus());
        assertEquals("WELCOME30", preview.getPromoCode());
        assertEquals(0, new BigDecimal("210").compareTo(preview.getItemsSubtotal()));
        assertEquals(0, new BigDecimal("30.00").compareTo(preview.getDiscount()));
        assertEquals(2, preview.getPerCart().size());
        assertEquals(cart1.getId(), preview.getPerCart().get(0).getCartId());
        assertEquals(0, new BigDecimal("30.00").compareTo(preview.getPerCart().get(0).getDiscount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(preview.getPerCart().get(1).getDiscount()));
        assertNull(preview.getMessage());
    }

    @Test
    @DisplayName("When the carts together are still short, the answer's 'add more' message is passed through")
    void notApplicableCarriesTheMessage() {
        UUID a = UUID.randomUUID();
        stubProduct(a, new BigDecimal("60"));
        givenCart(a, 1);
        when(couponService.quote(eq(userId), any(), any())).thenReturn(CouponQuote.notApplicable(couponId,
                "WELCOME30", "COUPON_MIN_ORDER_NOT_MET", "Add ₹60 more to get this discount (minimum order ₹120)."));

        CartPromoPreviewDto preview = cartService.previewPromo(userId, null, null);

        assertEquals("NOT_APPLICABLE", preview.getStatus());
        assertEquals("WELCOME30", preview.getPromoCode());
        assertEquals(0, BigDecimal.ZERO.compareTo(preview.getDiscount()));
        assertEquals("Add ₹60 more to get this discount (minimum order ₹120).", preview.getMessage());
    }

    @Test
    @DisplayName("With no coupon held the status is NONE and there is no discount")
    void noCouponHeld() {
        UUID a = UUID.randomUUID();
        stubProduct(a, new BigDecimal("60"));
        givenCart(a, 1);

        CartPromoPreviewDto preview = cartService.previewPromo(userId, null, null);

        assertEquals("NONE", preview.getStatus());
        assertNull(preview.getPromoCode());
        assertEquals(0, BigDecimal.ZERO.compareTo(preview.getDiscount()));
    }

    // ── which carts are valued ──────────────────────────────────────────────

    @Test
    @DisplayName("cartIds limits the valuation to those carts (the single-cart view)")
    void cartIdsLimitTheValuation() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stubProduct(a, new BigDecimal("60"));
        stubProduct(b, new BigDecimal("150"));
        Cart cart1 = givenCart(a, 1);
        givenCart(b, 1);

        cartService.previewPromo(userId, List.of(cart1.getId()), null);

        List<CouponMath.Basis> bases = capturedBases(null);
        assertEquals(List.of(cart1.getId()), bases.stream().map(CouponMath.Basis::key).collect(Collectors.toList()));
        assertEquals(0, new BigDecimal("60").compareTo(bases.get(0).items()));
    }

    @Test
    @DisplayName("cartIds that match no open cart are refused, like the checkout summary")
    void unknownCartIdsAreRefused() {
        UUID a = UUID.randomUUID();
        stubProduct(a, new BigDecimal("60"));
        givenCart(a, 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.previewPromo(userId, List.of(UUID.randomUUID()), null));

        assertEquals("CART_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("An empty bag is an empty preview, not an error, and the coupon is not valued")
    void emptyBagIsAnEmptyPreview() {
        CartPromoPreviewDto preview = cartService.previewPromo(userId, null, null);

        assertEquals("NONE", preview.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(preview.getItemsSubtotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(preview.getDiscount()));
        assertTrue(preview.getPerCart().isEmpty());
        verify(couponService, never()).quote(any(), any(), any());
    }

    @Test
    @DisplayName("An explicit couponCode is valued instead of the held one")
    void explicitCodeIsPassedThrough() {
        UUID a = UUID.randomUUID();
        stubProduct(a, new BigDecimal("60"));
        givenCart(a, 1);

        cartService.previewPromo(userId, null, "SAVE10");

        capturedBases("SAVE10");
    }
}
