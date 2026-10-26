// package com.veggofresh.customer.service;

// import com.veggofresh.admin.service.CouponService;
// import com.veggofresh.admin.service.PlatformSettingsService;
// import com.veggofresh.customer.dto.request.CartItemRequestDto;
// import com.veggofresh.customer.dto.response.CartResponseDto;
// import com.veggofresh.customer.entity.Address;
// import com.veggofresh.customer.entity.Cart;
// import com.veggofresh.customer.entity.CartItem;
// import com.veggofresh.customer.repository.AddressRepository;
// import com.veggofresh.customer.repository.CartItemRepository;
// import com.veggofresh.customer.repository.CartRepository;
// import com.veggofresh.customer.service.impl.CartServiceImpl;
// import com.veggofresh.platform.exception.BusinessException;
// import com.veggofresh.vendor.dto.ProductDto;
// import com.veggofresh.vendor.service.ProductCatalogService;

// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.Mock;
// import org.mockito.junit.jupiter.MockitoExtension;
// import org.mockito.junit.jupiter.MockitoSettings;
// import org.mockito.quality.Strictness;

// import java.math.BigDecimal;
// import java.util.ArrayList;
// import java.util.List;
// import java.util.Optional;
// import java.util.Set;
// import java.util.UUID;

// import static org.junit.jupiter.api.Assertions.assertEquals;
// import static org.junit.jupiter.api.Assertions.assertFalse;
// import static org.junit.jupiter.api.Assertions.assertNotNull;
// import static org.junit.jupiter.api.Assertions.assertNull;
// import static org.junit.jupiter.api.Assertions.assertThrows;
// import static org.junit.jupiter.api.Assertions.assertTrue;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.anyDouble;
// import static org.mockito.ArgumentMatchers.anyString;
// import static org.mockito.ArgumentMatchers.eq;
// import static org.mockito.Mockito.never;
// import static org.mockito.Mockito.times;
// import static org.mockito.Mockito.verify;
// import static org.mockito.Mockito.when;

// /**
//  * Covers the cart-count contract: what a customer sees, what the badge reports,
//  * and why a cart never lingers after its last item is removed.
//  */
// @ExtendWith(MockitoExtension.class)
// @MockitoSettings(strictness = Strictness.LENIENT)
// class CartServiceImplTest {

//     private static final double LAT = 12.9716;
//     private static final double LNG = 77.5946;
//     private static final BigDecimal DELIVERY_FEE = new BigDecimal("20");
//     private static final BigDecimal PLATFORM_FEE = new BigDecimal("5");

//     @Mock
//     private CartRepository cartRepository;
//     @Mock
//     private CartItemRepository cartItemRepository;
//     @Mock
//     private AddressRepository addressRepository;
//     @Mock
//     private ProductCatalogService productCatalogService;
//     @Mock
//     private CouponService couponService;
//     @Mock
//     private PlatformSettingsService platformSettingsService;

//     private CartServiceImpl cartService;

//     private final UUID userId = UUID.randomUUID();
//     private final UUID vendor1 = UUID.randomUUID();
//     private final UUID vendor2 = UUID.randomUUID();

//     /** Carts the repository currently returns, in creation order. */
//     private List<Cart> openCarts;

//     @BeforeEach
//     void setUp() {
//         cartService = new CartServiceImpl(cartRepository, cartItemRepository, addressRepository,
//                 productCatalogService, couponService, platformSettingsService,
//                 new CartVendorResolver(productCatalogService));

//         openCarts = new ArrayList<>();

//         Address address = new Address();
//         address.setUserId(userId);
//         address.setLatitude(LAT);
//         address.setLongitude(LNG);
//         address.setDefault(true);
//         when(addressRepository.findByUserId(userId)).thenReturn(List.of(address));

//         when(cartRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId))
//                 .thenAnswer(invocation -> openCarts.stream()
//                         .filter(cart -> !cart.isDeleted())
//                         .collect(java.util.stream.Collectors.toList()));
//         // A save of a brand-new cart has to make it visible to the next read,
//         // or the response after a first-time add would come back empty.
//         when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> {
//             Cart cart = invocation.getArgument(0);
//             if (cart.getId() == null) {
//                 cart.setId(UUID.randomUUID());
//             }
//             if (openCarts.stream().noneMatch(existing -> existing.getId().equals(cart.getId()))) {
//                 openCarts.add(cart);
//             }
//             return cart;
//         });
//         when(cartRepository.findByIdAndUserId(any(), any())).thenAnswer(invocation -> {
//             UUID cartId = invocation.getArgument(0);
//             UUID owner = invocation.getArgument(1);
//             return openCarts.stream()
//                     .filter(cart -> cart.getId().equals(cartId) && cart.getUserId().equals(owner))
//                     .findFirst();
//         });

//         when(platformSettingsService.getDeliveryFeeAmount()).thenReturn(DELIVERY_FEE);
//         when(platformSettingsService.getPlatformFeeAmount()).thenReturn(PLATFORM_FEE);
//     }

//     private void stubProduct(UUID productId, BigDecimal price, Set<UUID> vendors) {
//         when(productCatalogService.getShopIdsForProduct(productId, LAT, LNG)).thenReturn(vendors);
//         when(productCatalogService.findEligibleProductById(productId, LAT, LNG))
//                 .thenReturn(Optional.of(ProductDto.builder()
//                         .id(productId)
//                         .name("Product " + productId)
//                         .price(price)
//                         .unit("kg")
//                         .build()));
//     }

//     private void stubUnavailable(UUID productId) {
//         when(productCatalogService.getShopIdsForProduct(productId, LAT, LNG)).thenReturn(Set.of());
//         when(productCatalogService.findEligibleProductById(productId, LAT, LNG)).thenReturn(Optional.empty());
//     }

//     private Cart givenOpenCart(int[] quantities, UUID... productIds) {
//         Cart cart = new Cart();
//         cart.setId(UUID.randomUUID());
//         cart.setUserId(userId);
//         for (int i = 0; i < productIds.length; i++) {
//             CartItem item = new CartItem();
//             item.setId(UUID.randomUUID());
//             item.setCart(cart);
//             item.setProductId(productIds[i]);
//             item.setQuantity(quantities[i]);
//             cart.getItems().add(item);
//             // So that removal-style tests can act on a line without each one
//             // re-stubbing the ownership check.
//             when(cartItemRepository.findByIdAndCart_UserId(item.getId(), userId)).thenReturn(Optional.of(item));
//         }
//         openCarts.add(cart);
//         return cart;
//     }

//     private CartItemRequestDto addRequest(UUID productId, int quantity) {
//         CartItemRequestDto request = new CartItemRequestDto();
//         request.setProductId(productId);
//         request.setQuantity(quantity);
//         return request;
//     }

//     // ── the reported symptom: two carts must read as two carts ──────────────

//     @Test
//     @DisplayName("Two carts with items are returned as two, numbered Cart 1 and Cart 2")
//     void twoCartsRenderAsTwo() {
//         UUID a = UUID.randomUUID();
//         UUID b = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("10"), Set.of(vendor1));
//         stubProduct(b, new BigDecimal("20"), Set.of(vendor2));
//         givenOpenCart(new int[] { 1 }, a);
//         givenOpenCart(new int[] { 1 }, b);

//         List<CartResponseDto> carts = cartService.getOpenCarts(userId);

//         assertEquals(2, carts.size());
//         assertEquals("Cart 1", carts.get(0).getCartLabel());
//         assertEquals("Cart 2", carts.get(1).getCartLabel());
//     }

//     @Test
//     @DisplayName("A cart whose every product has become unavailable is not returned as an empty cart")
//     void cartWithOnlyUnavailableProductsIsHidden() {
//         UUID dead = UUID.randomUUID();
//         stubUnavailable(dead);
//         givenOpenCart(new int[] { 2 }, dead);

//         List<CartResponseDto> carts = cartService.getOpenCarts(userId);

//         // The row still holds an item, so a filter on rows alone keeps it -- and
//         // the client then renders a cart with an empty item list and a 0 total.
//         // That is the "empty cart" the customer reported still seeing.
//         assertTrue(carts.isEmpty(), "a cart that can show or charge no item at all must not be returned");
//     }

//     @Test
//     @DisplayName("Hiding an all-unavailable cart does not leave a gap in the labels")
//     void hidingUnavailableCartKeepsLabelsContiguous() {
//         UUID dead = UUID.randomUUID();
//         UUID good = UUID.randomUUID();
//         stubUnavailable(dead);
//         stubProduct(good, new BigDecimal("10"), Set.of(vendor1));
//         givenOpenCart(new int[] { 1 }, dead);
//         givenOpenCart(new int[] { 1 }, good);

//         List<CartResponseDto> carts = cartService.getOpenCarts(userId);

//         assertEquals(1, carts.size());
//         assertEquals("Cart 1", carts.get(0).getCartLabel(),
//                 "the survivor fills the gap rather than staying labelled Cart 2");
//     }

//     @Test
//     @DisplayName("A cart mixing available and unavailable products keeps its buyable lines")
//     void partiallyAvailableCartKeepsItsBuyableLines() {
//         UUID good = UUID.randomUUID();
//         UUID dead = UUID.randomUUID();
//         stubProduct(good, new BigDecimal("10"), Set.of(vendor1));
//         stubUnavailable(dead);
//         givenOpenCart(new int[] { 3, 4 }, good, dead);

//         List<CartResponseDto> carts = cartService.getOpenCarts(userId);

//         assertEquals(1, carts.size());
//         assertEquals(1, carts.get(0).getItems().size(), "only the buyable line is rendered");
//         assertEquals(3, carts.get(0).getItemCount());
//         assertEquals(1, carts.get(0).getUnavailableItemCount());
//     }

//     @Test
//     @DisplayName("Removing the last item retires that cart and renumbers what remains")
//     void removingLastItemRetiresTheCart() {
//         UUID a = UUID.randomUUID();
//         UUID b = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("10"), Set.of(vendor1));
//         stubProduct(b, new BigDecimal("20"), Set.of(vendor2));
//         givenOpenCart(new int[] { 1 }, a);
//         Cart second = givenOpenCart(new int[] { 1 }, b);

//         CartItem doomed = second.getItems().get(0);
//         when(cartItemRepository.findByIdAndCart_UserId(doomed.getId(), userId)).thenReturn(Optional.of(doomed));

//         List<CartResponseDto> carts = cartService.removeCartItem(userId, doomed.getId());

//         assertTrue(second.isDeleted(), "the emptied cart must be soft-deleted");
//         assertEquals(1, carts.size(), "two carts, one emptied, must read as exactly one");
//         assertEquals("Cart 1", carts.get(0).getCartLabel(),
//                 "the surviving cart must be Cart 1, not left labelled Cart 2");
//         assertEquals(1, carts.get(0).getItemCount());
//     }

//     @Test
//     @DisplayName("Removing one item from a multi-item cart keeps that cart open")
//     void removingOneOfManyItemsKeepsCartOpen() {
//         UUID a = UUID.randomUUID();
//         UUID b = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("10"), Set.of(vendor1));
//         stubProduct(b, new BigDecimal("20"), Set.of(vendor1));
//         Cart cart = givenOpenCart(new int[] { 1, 1 }, a, b);

//         CartItem doomed = cart.getItems().get(0);
//         when(cartItemRepository.findByIdAndCart_UserId(doomed.getId(), userId)).thenReturn(Optional.of(doomed));

//         List<CartResponseDto> carts = cartService.removeCartItem(userId, doomed.getId());

//         assertFalse(cart.isDeleted());
//         assertEquals(1, carts.size());
//         assertEquals(1, carts.get(0).getItemCount());
//     }

//     @Test
//     @DisplayName("A pre-existing ghost cart is retired on the next add and never absorbs the item")
//     void ghostCartIsCleanedUpOnAdd() {
//         UUID productA = UUID.randomUUID();
//         UUID incoming = UUID.randomUUID();
//         stubProduct(productA, new BigDecimal("10"), Set.of(vendor1));
//         stubProduct(incoming, new BigDecimal("20"), Set.of(vendor1));

//         Cart real = givenOpenCart(new int[] { 1 }, productA);
//         Cart ghost = new Cart();
//         ghost.setId(UUID.randomUUID());
//         ghost.setUserId(userId);
//         ghost.setCandidateVendorIds(Set.of(vendor1));
//         openCarts.add(ghost);

//         List<CartResponseDto> carts = cartService.addItemToCart(userId, addRequest(incoming, 1));

//         assertTrue(ghost.isDeleted());
//         assertEquals(1, carts.size());
//         assertEquals(2, real.getItems().size(), "the item must land in the live cart, not the ghost");
//     }

//     @Test
//     @DisplayName("Ghost carts are never returned to the client even if they were never cleaned up")
//     void ghostCartsAreFilteredFromReads() {
//         UUID a = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("10"), Set.of(vendor1));
//         givenOpenCart(new int[] { 1 }, a);
//         Cart ghost = new Cart();
//         ghost.setId(UUID.randomUUID());
//         ghost.setUserId(userId);
//         openCarts.add(ghost);

//         List<CartResponseDto> carts = cartService.getOpenCarts(userId);

//         assertEquals(1, carts.size());
//         assertEquals("Cart 1", carts.get(0).getCartLabel());
//     }

//     // ── itemCount must mean the same thing everywhere ────────────────────────

//     @Test
//     @DisplayName("itemCount sums quantities, not the number of line items")
//     void itemCountSumsQuantities() {
//         UUID a = UUID.randomUUID();
//         UUID b = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("10"), Set.of(vendor1));
//         stubProduct(b, new BigDecimal("20"), Set.of(vendor1));
//         givenOpenCart(new int[] { 2, 5 }, a, b);

//         List<CartResponseDto> carts = cartService.getOpenCarts(userId);

//         assertEquals(7, carts.get(0).getItemCount(), "2 + 5 units is 7 items, not 2 lines");
//         assertEquals(new BigDecimal("120"), carts.get(0).getTotalAmount(), "2*10 + 5*20");
//     }

//     @Test
//     @DisplayName("Unavailable lines are excluded from both the count and the money, and reported separately")
//     void unavailableLinesAreExcludedAndCounted() {
//         UUID alive = UUID.randomUUID();
//         UUID dead = UUID.randomUUID();
//         stubProduct(alive, new BigDecimal("10"), Set.of(vendor1));
//         stubUnavailable(dead);
//         givenOpenCart(new int[] { 3, 4 }, alive, dead);

//         CartResponseDto cart = cartService.getOpenCarts(userId).get(0);

//         assertEquals(3, cart.getItemCount());
//         assertEquals(new BigDecimal("30"), cart.getTotalAmount());
//         assertEquals(1, cart.getUnavailableItemCount());
//     }

//     @Test
//     @DisplayName("The badge comes from one aggregate query, not from loading the cart graph")
//     void badgeUsesAggregateQuery() {
//         when(cartRepository.sumItemQuantities(userId)).thenReturn(7);

//         assertEquals(7, cartService.getCartCount(userId));

//         verify(cartRepository).sumItemQuantities(userId);
//         verify(cartRepository, never()).findByUserIdOrderByCreatedAtAscIdAsc(userId);
//     }

//     // ── money ───────────────────────────────────────────────────────────────

//     @Test
//     @DisplayName("payableAmount is subtotal plus both fees, never negative")
//     void payableAmountIsClampedAtZero() {
//         UUID a = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("10"), Set.of(vendor1));
//         Cart cart = givenOpenCart(new int[] { 1 }, a);
//         // A discount larger than the whole order, e.g. a coupon validated
//         // against a bigger basis before the customer removed items.
//         cart.setPromoCode("STALE");
//         cart.setPromoDiscount(new BigDecimal("500"));

//         CartResponseDto dto = cartService.getOpenCarts(userId).get(0);

//         assertEquals(BigDecimal.ZERO, dto.getPayableAmount(),
//                 "a stale discount must not produce a negative charge");
//     }

//     @Test
//     @DisplayName("payableAmount adds delivery fee and platform fee to the subtotal")
//     void payableAmountAddsBothFees() {
//         UUID a = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("10"), Set.of(vendor1));
//         givenOpenCart(new int[] { 3 }, a);

//         CartResponseDto dto = cartService.getOpenCarts(userId).get(0);

//         assertEquals(new BigDecimal("30"), dto.getTotalAmount());
//         assertEquals(new BigDecimal("55"), dto.getPayableAmount(), "30 + 20 delivery + 5 platform");
//     }

//     @Test
//     @DisplayName("A discount larger than the subtotal is capped rather than producing a negative total")
//     void oversizedPromoIsCapped() {
//         UUID a = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("100"), Set.of(vendor1));
//         givenOpenCart(new int[] { 1 }, a);
//         when(couponService.validateCoupon("HUGE", new BigDecimal("100"))).thenReturn(new BigDecimal("500"));

//         List<CartResponseDto> carts = cartService.applyPromoCode(userId, openCarts.get(0).getId(), "HUGE");

//         assertEquals(new BigDecimal("100"), carts.get(0).getPromoDiscount(),
//                 "a discount can never exceed what it discounts");
//     }

//     @Test
//     @DisplayName("A promo is dropped when its cart drops below the qualifying subtotal")
//     void promoIsDroppedWhenCartShrinks() {
//         UUID a = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("100"), Set.of(vendor1));
//         Cart cart = givenOpenCart(new int[] { 1 }, a);
//         cart.setPromoCode("SAVE10");
//         cart.setPromoDiscount(new BigDecimal("10"));
//         when(couponService.validateCoupon(anyString(), any(BigDecimal.class))).thenReturn(BigDecimal.ZERO);

//         CartItem item = cart.getItems().get(0);
//         when(cartItemRepository.findByIdAndCart_UserId(item.getId(), userId)).thenReturn(Optional.of(item));

//         List<CartResponseDto> carts = cartService.updateCartItem(userId, item.getId(), 1);

//         assertNull(carts.get(0).getPromoCode(), "a promo that no longer applies must not linger");
//     }

//     // ── input validation ────────────────────────────────────────────────────

//     @Test
//     @DisplayName("Adding a zero or negative quantity is rejected")
//     void addRejectsNonPositiveQuantity() {
//         BusinessException zero = assertThrows(BusinessException.class,
//                 () -> cartService.addItemToCart(userId, addRequest(UUID.randomUUID(), 0)));
//         BusinessException negative = assertThrows(BusinessException.class,
//                 () -> cartService.addItemToCart(userId, addRequest(UUID.randomUUID(), -3)));

//         assertEquals("INVALID_QUANTITY", zero.getErrorCode());
//         assertEquals("INVALID_QUANTITY", negative.getErrorCode());
//     }

//     @Test
//     @DisplayName("Setting quantity to zero or below removes the line instead of storing a zero row")
//     void updateToNonPositiveQuantityRemovesTheLine() {
//         UUID productA = UUID.randomUUID();
//         UUID productB = UUID.randomUUID();
//         stubProduct(productA, new BigDecimal("10"), Set.of(UUID.randomUUID()));
//         stubProduct(productB, new BigDecimal("10"), Set.of(UUID.randomUUID()));
//         Cart cart = givenOpenCart(new int[] { 2, 1 }, productA, productB);

//         cartService.updateCartItem(userId, cart.getItems().get(0).getId(), 0);

//         verify(cartItemRepository).delete(any(CartItem.class));
//         assertFalse(cart.isDeleted(), "the cart still holds another line, so it stays open");
//     }

//     @Test
//     @DisplayName("Dropping the last line to zero retires the whole cart rather than leaving a ghost")
//     void updateLastItemToZeroRetiresCart() {
//         UUID productA = UUID.randomUUID();
//         stubProduct(productA, new BigDecimal("10"), Set.of(UUID.randomUUID()));
//         Cart cart = givenOpenCart(new int[] { 1 }, productA);

//         cartService.updateCartItem(userId, cart.getItems().get(0).getId(), 0);

//         assertTrue(cart.isDeleted(), "no ghost cart may be left open");
//     }

//     @Test
//     @DisplayName("A product no vendor carries is rejected on add")
//     void addRejectsUnavailableProduct() {
//         UUID productId = UUID.randomUUID();
//         stubUnavailable(productId);

//         BusinessException ex = assertThrows(BusinessException.class,
//                 () -> cartService.addItemToCart(userId, addRequest(productId, 1)));

//         assertEquals("PRODUCT_NOT_AVAILABLE", ex.getErrorCode());
//     }

//     @Test
//     @DisplayName("A promo cannot be applied to a cart with nothing in it")
//     void promoRejectedOnEmptyCart() {
//         Cart empty = new Cart();
//         empty.setId(UUID.randomUUID());
//         empty.setUserId(userId);
//         openCarts.add(empty);

//         BusinessException ex = assertThrows(BusinessException.class,
//                 () -> cartService.applyPromoCode(userId, empty.getId(), "FRESH10"));

//         assertEquals("CART_EMPTY", ex.getErrorCode());
//     }

//     @Test
//     @DisplayName("Another customer's cart cannot have a promo applied to it")
//     void promoRejectedForForeignCart() {
//         Cart foreign = new Cart();
//         foreign.setId(UUID.randomUUID());
//         foreign.setUserId(UUID.randomUUID());
//         openCarts.add(foreign);

//         BusinessException ex = assertThrows(BusinessException.class,
//                 () -> cartService.applyPromoCode(userId, foreign.getId(), "FRESH10"));

//         assertEquals("CART_NOT_FOUND", ex.getErrorCode());
//     }

//     @Test
//     @DisplayName("Applying a coupon the service rejects surfaces as a clear error")
//     void promoRejectedWhenCouponServiceReturnsNothing() {
//         UUID a = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("100"), Set.of(vendor1));
//         givenOpenCart(new int[] { 1 }, a);
//         when(couponService.validateCoupon("BAD", new BigDecimal("100"))).thenReturn(BigDecimal.ZERO);

//         BusinessException ex = assertThrows(BusinessException.class,
//                 () -> cartService.applyPromoCode(userId, openCarts.get(0).getId(), "BAD"));

//         assertEquals("INVALID_PROMO_CODE", ex.getErrorCode());
//     }

//     // ── adding the same product twice ───────────────────────────────────────

//     @Test
//     @DisplayName("Re-adding a product merges into the existing line rather than adding a second line")
//     void readdingSameProductMergesQuantity() {
//         UUID productId = UUID.randomUUID();
//         stubProduct(productId, new BigDecimal("10"), Set.of(vendor1));
//         Cart cart = givenOpenCart(new int[] { 2 }, productId);

//         List<CartResponseDto> carts = cartService.addItemToCart(userId, addRequest(productId, 3));

//         assertEquals(1, openCarts.size(), "merging must not open a second cart");
//         assertEquals(1, cart.getItems().size());
//         assertEquals(5, cart.getItems().get(0).getQuantity());
//         assertEquals(5, carts.get(0).getItemCount());
//     }

//     @Test
//     @DisplayName("A first add for a new product opens exactly one cart")
//     void firstAddOpensOneCart() {
//         UUID productId = UUID.randomUUID();
//         stubProduct(productId, new BigDecimal("10"), Set.of(vendor1));

//         List<CartResponseDto> carts = cartService.addItemToCart(userId, addRequest(productId, 2));

//         assertEquals(1, carts.size());
//         assertEquals("Cart 1", carts.get(0).getCartLabel());
//         assertEquals(2, carts.get(0).getItemCount());
//         assertNotNull(carts.get(0).getId());
//     }

//     @Test
//     @DisplayName("A product from an unrelated vendor opens a second cart, giving two not one")
//     void unrelatedVendorOpensSecondCart() {
//         UUID a = UUID.randomUUID();
//         UUID b = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("10"), Set.of(vendor1));
//         stubProduct(b, new BigDecimal("20"), Set.of(vendor2));
//         givenOpenCart(new int[] { 1 }, a);

//         List<CartResponseDto> carts = cartService.addItemToCart(userId, addRequest(b, 1));

//         assertEquals(2, carts.size());
//         assertEquals("Cart 1", carts.get(0).getCartLabel());
//         assertEquals("Cart 2", carts.get(1).getCartLabel());
//     }

//     // ── addresses ───────────────────────────────────────────────────────────

//     @Test
//     @DisplayName("Cart operations require a saved address, since eligibility depends on location")
//     void addressIsRequired() {
//         when(addressRepository.findByUserId(userId)).thenReturn(List.of());

//         BusinessException ex = assertThrows(BusinessException.class,
//                 () -> cartService.getOpenCarts(userId));

//         assertEquals("ADDRESS_REQUIRED", ex.getErrorCode());
//     }

//     @Test
//     @DisplayName("The default address is preferred over the customer's other saved addresses")
//     void defaultAddressIsPreferred() {
//         Address secondary = new Address();
//         secondary.setUserId(userId);
//         secondary.setLatitude(1.0);
//         secondary.setLongitude(2.0);
//         secondary.setDefault(false);
//         Address primary = new Address();
//         primary.setUserId(userId);
//         primary.setLatitude(LAT);
//         primary.setLongitude(LNG);
//         primary.setDefault(true);
//         when(addressRepository.findByUserId(userId)).thenReturn(List.of(secondary, primary));

//         UUID productId = UUID.randomUUID();
//         when(productCatalogService.getShopIdsForProduct(productId, LAT, LNG)).thenReturn(Set.of(vendor1));
//         when(productCatalogService.findEligibleProductById(productId, LAT, LNG)).thenReturn(Optional.of(
//                 ProductDto.builder().id(productId).name("P").price(new BigDecimal("10")).unit("kg").build()));

//         cartService.addItemToCart(userId, addRequest(productId, 1));

//         verify(productCatalogService).getShopIdsForProduct(productId, LAT, LNG);
//     }

//     // ── read-only badge must not need the address ───────────────────────────

//     @Test
//     @DisplayName("The badge count works even with no saved address")
//     void badgeDoesNotRequireAddress() {
//         when(addressRepository.findByUserId(userId)).thenReturn(List.of());
//         when(cartRepository.sumItemQuantities(userId)).thenReturn(4);

//         assertEquals(4, cartService.getCartCount(userId));
//     }

//     @Test
//     @DisplayName("Fee configuration is resolved once for the whole response, and each product is looked up once")
//     void feesAreResolvedOnce() {
//         UUID a = UUID.randomUUID();
//         UUID b = UUID.randomUUID();
//         stubProduct(a, new BigDecimal("10"), Set.of(vendor1));
//         stubProduct(b, new BigDecimal("20"), Set.of(vendor2));
//         givenOpenCart(new int[] { 1 }, a);
//         givenOpenCart(new int[] { 1 }, b);

//         cartService.getOpenCarts(userId);

//         // The fees are identical for every cart, so they must not be re-read
//         // per cart.
//         verify(platformSettingsService).getDeliveryFeeAmount();
//         verify(platformSettingsService).getPlatformFeeAmount();

//         // Deciding which carts are worth showing means asking which products are
//         // still buyable, so the catalog IS consulted here now. What matters is
//         // that it is consulted once per distinct product rather than once per
//         // cart, per item, or again for the pricing pass.
//         verify(productCatalogService).getShopIdsForProduct(a, LAT, LNG);
//         verify(productCatalogService).getShopIdsForProduct(b, LAT, LNG);
//         verify(productCatalogService, times(1)).findEligibleProductById(eq(a), anyDouble(), anyDouble());
//         verify(productCatalogService, times(1)).findEligibleProductById(eq(b), anyDouble(), anyDouble());
//     }
// }
