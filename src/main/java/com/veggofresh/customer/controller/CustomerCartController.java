package com.veggofresh.customer.controller;

import com.veggofresh.customer.dto.request.CartItemRequestDto;
import com.veggofresh.customer.dto.request.PromoCodeRequestDto;
import com.veggofresh.customer.dto.response.CartResponseDto;
import com.veggofresh.customer.service.CartService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import com.veggofresh.vendor.dto.ProductDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PHASE 2 — NEW ARCHITECTURE, multi-cart model (PROJECT_STATE section 2).
 *
 * ⚠️ BREAKING CHANGE from the pre-pivot single-cart API:
 *  - Base path moved from /api/customer/cart to /api/customer/carts (plural).
 *  - Add-item moved from POST /api/customer/cart to POST /api/customer/carts/items
 *    (base path now returns the LIST of carts, so it needed to free up for GET).
 *  - Every mutating endpoint now returns the FULL list of the customer's open
 *    carts, not a single cart, since one add-to-cart call can change which
 *    cart an item lands in.
 *  - Promo-code endpoints now require a cartId path segment
 *    (/carts/{cartId}/promo-code), since promo is applied per-cart.
 *
 * Front-end needs to switch to rendering a "Cart 1 / Cart 2 / ..." list from
 * every response instead of a single cart object.
 */
@RestController
@RequestMapping("/api/customer/carts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerCartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CartResponseDto>>> getCarts() {
        List<CartResponseDto> carts = cartService.getOpenCarts(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(carts, "Carts retrieved successfully"));
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<List<CartResponseDto>>> addItem(
            @Valid @RequestBody CartItemRequestDto request) {
        List<CartResponseDto> carts = cartService.addItemToCart(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(carts, "Item added to cart successfully"));
    }

    @PutMapping("/items/{id}")
    public ResponseEntity<ApiResponse<List<CartResponseDto>>> updateItemQuantity(
            @PathVariable UUID id,
            @RequestParam int quantity) {
        List<CartResponseDto> carts = cartService.updateCartItem(SecurityUtils.getCurrentUserId(), id, quantity);
        return ResponseEntity.ok(ApiResponse.success(carts, "Cart item updated successfully"));
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<ApiResponse<List<CartResponseDto>>> removeItem(
            @PathVariable UUID id) {
        List<CartResponseDto> carts = cartService.removeCartItem(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(carts, "Item removed from cart successfully"));
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> getCartCount() {
        int count = cartService.getCartCount(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("count", count), "Cart badge count retrieved successfully"));
    }

    @GetMapping("/recommendations")
    public ResponseEntity<ApiResponse<List<ProductDto>>> getRecommendations() {
        List<ProductDto> products = cartService.getCartRecommendations(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(products, "Cart recommendations retrieved successfully"));
    }

    @PostMapping("/{cartId}/promo-code")
    public ResponseEntity<ApiResponse<List<CartResponseDto>>> applyPromoCode(
            @PathVariable UUID cartId,
            @Valid @RequestBody PromoCodeRequestDto request) {
        List<CartResponseDto> carts = cartService.applyPromoCode(SecurityUtils.getCurrentUserId(), cartId, request.getCode());
        return ResponseEntity.ok(ApiResponse.success(carts, "Promo code applied successfully"));
    }

    @DeleteMapping("/{cartId}/promo-code")
    public ResponseEntity<ApiResponse<List<CartResponseDto>>> removePromoCode(
            @PathVariable UUID cartId) {
        List<CartResponseDto> carts = cartService.removePromoCode(SecurityUtils.getCurrentUserId(), cartId);
        return ResponseEntity.ok(ApiResponse.success(carts, "Promo code removed successfully"));
    }
}
