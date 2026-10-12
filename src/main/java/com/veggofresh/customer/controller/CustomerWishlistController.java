package com.veggofresh.customer.controller;

import com.veggofresh.customer.service.WishlistService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.security.SecurityUtils;
import com.veggofresh.vendor.dto.ProductDto;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/customer/wishlist")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerWishlistController {

    private final WishlistService wishlistService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductDto>>> getWishlist(
            @RequestParam(required = false) String category) {
        List<ProductDto> wishlist;
        if (category != null && !category.trim().isEmpty()) {
            wishlist = wishlistService.getWishlistByCategory(SecurityUtils.getCurrentUserId(), category);
        } else {
            wishlist = wishlistService.getWishlist(SecurityUtils.getCurrentUserId());
        }
        return ResponseEntity.ok(ApiResponse.success(wishlist, "Wishlist retrieved successfully"));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> addToWishlist(
            @RequestParam UUID productId) {
        wishlistService.addToWishlist(SecurityUtils.getCurrentUserId(), productId);
        return ResponseEntity.ok(ApiResponse.success("Product added to wishlist successfully"));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<ApiResponse<Void>> removeFromWishlist(
            @PathVariable UUID productId) {
        wishlistService.removeFromWishlist(SecurityUtils.getCurrentUserId(), productId);
        return ResponseEntity.ok(ApiResponse.success("Product removed from wishlist successfully"));
    }

    @GetMapping("/recommendations")
    public ResponseEntity<ApiResponse<List<ProductDto>>> getRecommendations() {
        List<ProductDto> products = wishlistService.getWishlistRecommendations(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(products, "Wishlist recommendations retrieved successfully"));
    }
}
