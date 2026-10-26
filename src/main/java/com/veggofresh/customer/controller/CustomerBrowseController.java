package com.veggofresh.customer.controller;

import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import com.veggofresh.vendor.dto.CategoryDto;
import com.veggofresh.vendor.dto.CategoryTreeDto;
import com.veggofresh.vendor.dto.ProductDto;
import com.veggofresh.vendor.dto.ShopDto;
import com.veggofresh.vendor.dto.SubcategoryDto;
import com.veggofresh.vendor.service.ProductCatalogService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * BREAKING CHANGE this round: /products' `category` request param used to be
 * a free-text name matched case-insensitively against Admin's category list --
 * fragile, and inconsistent with every other filter in the system. It's now
 * `categoryId` (+ new `subcategoryId`), real UUIDs. Frontend gets those from
 * the new /categories and /categories/{categoryId}/subcategories endpoints
 * below -- it should never type or guess one, same flow as Vendor's category
 * picker.
 */
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerBrowseController {

    private final ProductCatalogService productCatalogService;

    @GetMapping("/shops")
    public ResponseEntity<ApiResponse<List<ShopDto>>> browseShops(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        List<ShopDto> shops = productCatalogService.browseNearbyShops(latitude, longitude);
        return ResponseEntity.ok(ApiResponse.success(shops, "Shops retrieved successfully"));
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<PageResponse<CategoryDto>>> browseCategories(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CategoryDto> categories = productCatalogService.browseCategories(search, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(categories), "Categories retrieved successfully"));
    }

    @GetMapping("/categories/{categoryId}/subcategories")
    public ResponseEntity<ApiResponse<PageResponse<SubcategoryDto>>> browseSubcategories(
            @PathVariable UUID categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SubcategoryDto> subcategories = productCatalogService.browseSubcategories(categoryId, search, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(subcategories), "Subcategories retrieved successfully"));
    }

    /**
     * NEW -- one category, its subcategories, and each subcategory's eligible
     * (radius-filtered, listed) products, all nested in one response. Category/
     * subcategory data itself is not radius-filtered, matching /categories and
     * /categories/{categoryId}/subcategories above; only the nested products are.
     */
    @GetMapping("/categories/{categoryId}/tree")
    public ResponseEntity<ApiResponse<CategoryTreeDto>> getCategoryTree(
            @PathVariable UUID categoryId,
            @RequestParam double latitude,
            @RequestParam double longitude) {
        CategoryTreeDto tree = productCatalogService.getCategoryTree(categoryId, latitude, longitude);
        return ResponseEntity.ok(ApiResponse.success(tree, "Category tree retrieved successfully"));
    }

    /**
     * NEW -- the entire active catalog, every category with every subcategory with
     * every eligible product nested inside, in one response. Testing/tooling use,
     * not the production app -- cost scales with total catalog size since radius-
     * eligibility runs per product across every subcategory in a single call.
     */
    @GetMapping("/categories/tree")
    public ResponseEntity<ApiResponse<List<CategoryTreeDto>>> getFullCatalogTree(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        List<CategoryTreeDto> tree = productCatalogService.getFullCatalogTree(latitude, longitude);
        return ResponseEntity.ok(ApiResponse.success(tree, "Full catalog tree retrieved successfully"));
    }

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<PageResponse<ProductDto>>> searchProducts(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID subcategoryId,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<ProductDto> products = productCatalogService.searchProducts(
                query, categoryId, subcategoryId, minPrice, maxPrice, latitude, longitude, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(products), "Products retrieved successfully"));
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductDto>> getProduct(
            @PathVariable UUID id,
            @RequestParam double latitude,
            @RequestParam double longitude) {
        ProductDto product = productCatalogService.getProductById(id, latitude, longitude);
        return ResponseEntity.ok(ApiResponse.success(product, "Product retrieved successfully"));
    }

    @GetMapping("/categories/all")
    public ResponseEntity<ApiResponse<List<CategoryDto>>> getCategories() {
        List<CategoryDto> categories = productCatalogService.getAllCategories();
        return ResponseEntity.ok(ApiResponse.success(categories, "Categories retrieved successfully"));
    }

    @GetMapping("/products/{id}/related")
    public ResponseEntity<ApiResponse<List<ProductDto>>> getRelatedProducts(
            @PathVariable UUID id,
            @RequestParam double latitude,
            @RequestParam double longitude) {
        List<ProductDto> related = productCatalogService.getRelatedProducts(id, latitude, longitude);
        return ResponseEntity.ok(ApiResponse.success(related, "Related products retrieved successfully"));
    }

    @GetMapping("/products/deals")
    public ResponseEntity<ApiResponse<List<ProductDto>>> getDailyDeals(
            @RequestParam double latitude,
            @RequestParam double longitude) {
        List<ProductDto> deals = productCatalogService.getDailyDeals(latitude, longitude);
        return ResponseEntity.ok(ApiResponse.success(deals, "Daily deals retrieved successfully"));
    }

    // GET /api/customer/banners now lives in com.veggofresh.banner.controller.CustomerBannerController
    // (admin-managed banners from the database instead of two hard-coded fake ones).
}
