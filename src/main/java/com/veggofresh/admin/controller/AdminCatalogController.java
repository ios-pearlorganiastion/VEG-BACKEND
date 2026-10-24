package com.veggofresh.admin.controller;

import com.veggofresh.admin.dto.request.CatalogMoveRequestDto;
import com.veggofresh.admin.dto.request.CategoryRequestDto;
import com.veggofresh.admin.dto.request.ProductCreateRequestDto;
import com.veggofresh.admin.dto.request.ProductImageReorderRequestDto;
import com.veggofresh.admin.dto.request.ProductRequestDto;
import com.veggofresh.admin.dto.request.SubcategoryRequestDto;
import com.veggofresh.admin.dto.response.CatalogMoveResultDto;
import com.veggofresh.admin.dto.response.CategoryResponseDto;
import com.veggofresh.admin.dto.response.CategoryTreeDto;
import com.veggofresh.admin.dto.response.ProductImageResponseDto;
import com.veggofresh.admin.dto.response.ProductResponseDto;
import com.veggofresh.admin.dto.response.SubcategoryResponseDto;
import com.veggofresh.admin.service.AdminProductService;
import com.veggofresh.admin.service.CatalogCategoryService;
import com.veggofresh.admin.service.CatalogSubcategoryService;
import com.veggofresh.admin.service.CatalogTreeService;
import com.veggofresh.platform.common.ApiResponse;
import com.veggofresh.platform.common.PageResponse;
import com.veggofresh.platform.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Admin Master Product Catalog Controller (Phase 1)
 *
 * <pre>
 * ── Categories ───────────────────────────────────────────────
 * GET    /api/admin/catalog/categories?includeInactive=   -- plain list (unpaginated); kept until the admin panel moves to /search
 * GET    /api/admin/catalog/categories/search?search=&status=&page=&size=  -- NEW paginated list/type-ahead, ALL categories, display order
 * POST   /api/admin/catalog/categories                    -- multipart/form-data (name, description, image?) -- appended to the bottom
 * GET    /api/admin/catalog/categories/{id}
 * PUT    /api/admin/catalog/categories/{id}                -- multipart/form-data (image optional -- omit to keep current)
 * PATCH  /api/admin/catalog/categories/{id}/status?active=
 * PATCH  /api/admin/catalog/categories/{id}/move               -- NEW JSON { "afterId": uuid } OR { "position": n }
 *
 * ── Subcategories ────────────────────────────────────────────
 * GET    /api/admin/catalog/subcategories?categoryId=      -- plain list (unpaginated); kept until the admin panel moves to /search
 * GET    /api/admin/catalog/subcategories/search?categoryId=&search=&status=&page=&size=  -- NEW paginated list/type-ahead, ALL subcategories of a category
 * POST   /api/admin/catalog/subcategories                 -- multipart/form-data (categoryId, name, image?) -- appended to the bottom of its category
 * GET    /api/admin/catalog/subcategories/{id}
 * PUT    /api/admin/catalog/subcategories/{id}             -- multipart/form-data (image optional -- omit to keep current)
 * PATCH  /api/admin/catalog/subcategories/{id}/status?active=
 * PATCH  /api/admin/catalog/subcategories/{id}/move            -- NEW JSON { "afterId": uuid } OR { "position": n }
 *
 * ── Products ─────────────────────────────────────────────────
 * GET    /api/admin/catalog/products?search=&categoryId=&subcategoryId=&page=&size=
 * POST   /api/admin/catalog/products                       -- multipart/form-data, "images" (1+ files) REQUIRED
 * GET    /api/admin/catalog/products/{id}
 * PUT    /api/admin/catalog/products/{id}                  -- JSON, text/pricing fields only, images untouched
 * PATCH  /api/admin/catalog/products/{id}/status?active=
 *
 * ── Product images ───────────────────────────────────────────
 * GET    /api/admin/catalog/products/{id}/images
 * POST   /api/admin/catalog/products/{id}/images            -- multipart/form-data, "images" (1+ files)
 * DELETE /api/admin/catalog/products/{id}/images/{imageId}  -- refuses to delete the last remaining image
 * PUT    /api/admin/catalog/products/{id}/images/reorder     -- JSON { "imageIds": [...] }, full reorder, position 0 = cover
 *
 * ── Combined tree (NEW) ──────────────────────────────────────
 * GET    /api/admin/catalog/categories/{id}/tree  -- one category, subcategories + products nested, no filtering
 * GET    /api/admin/catalog/tree                  -- entire catalog, same nesting. Testing/tooling use --
 *                                                     cost scales with catalog size.
 *
 * No hard-delete endpoints anywhere in this controller, deliberately —
 * see NOTES_ADMIN.md, "Catalog: no hard delete."
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/catalog")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogController {

    private final CatalogCategoryService categoryService;
    private final CatalogSubcategoryService subcategoryService;
    private final CatalogTreeService catalogTreeService;
    private final AdminProductService adminProductService;

    // ── CATEGORIES ───────────────────────────────────────────────────────

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryResponseDto>>> listCategories(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(ApiResponse.success(
                categoryService.listCategories(includeInactive), "Categories retrieved successfully"));
    }

    /**
     * Paginated list + type-ahead over ALL categories (active and inactive) in display order.
     * {@code status}: all (default) | active | inactive. Works as the ordering screen's list
     * (status=all, size ~30, infinite scroll) and as a picker/search box (search=..., size ~20).
     */
    @GetMapping("/categories/search")
    public ResponseEntity<ApiResponse<PageResponse<CategoryResponseDto>>> searchCategories(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CategoryResponseDto> result = categoryService.searchCategories(
                search, parseStatus(status), PageRequest.of(Math.max(page, 0), clampSize(size)));
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(result), "Categories retrieved successfully"));
    }

    @PostMapping(value = "/categories", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CategoryResponseDto>> createCategory(
            @Valid @ModelAttribute CategoryRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                categoryService.createCategory(request), "Category created successfully"));
    }

    @GetMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<CategoryResponseDto>> getCategory(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                categoryService.getCategoryById(id), "Category retrieved successfully"));
    }

    @PutMapping(value = "/categories/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CategoryResponseDto>> updateCategory(
            @PathVariable UUID id, @Valid @ModelAttribute CategoryRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                categoryService.updateCategory(id, request), "Category updated successfully"));
    }

    @PatchMapping("/categories/{id}/status")
    public ResponseEntity<ApiResponse<CategoryResponseDto>> setCategoryStatus(
            @PathVariable UUID id, @RequestParam boolean active) {
        return ResponseEntity.ok(ApiResponse.success(
                categoryService.setActive(id, active),
                active ? "Category activated successfully" : "Category deactivated successfully"));
    }

    /**
     * Moves one category within the full category list (active and inactive). Body: exactly one of
     * {@code afterId} (place after this category) or {@code position} (1 = top; past the end = bottom).
     * Returns the categories whose position changed so the panel can patch its local list.
     */
    @PatchMapping("/categories/{id}/move")
    public ResponseEntity<ApiResponse<CatalogMoveResultDto>> moveCategory(
            @PathVariable UUID id, @Valid @RequestBody CatalogMoveRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                categoryService.moveCategory(id, request), "Category moved successfully"));
    }

    // ── SUBCATEGORIES ────────────────────────────────────────────────────

    @GetMapping("/subcategories")
    public ResponseEntity<ApiResponse<List<SubcategoryResponseDto>>> listSubcategories(
            @RequestParam UUID categoryId) {
        return ResponseEntity.ok(ApiResponse.success(
                subcategoryService.listByCategory(categoryId), "Subcategories retrieved successfully"));
    }

    /**
     * Paginated list + type-ahead over ALL subcategories (active and inactive) of one category,
     * in display order. {@code status}: all (default) | active | inactive.
     */
    @GetMapping("/subcategories/search")
    public ResponseEntity<ApiResponse<PageResponse<SubcategoryResponseDto>>> searchSubcategories(
            @RequestParam UUID categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SubcategoryResponseDto> result = subcategoryService.searchSubcategories(
                categoryId, search, parseStatus(status), PageRequest.of(Math.max(page, 0), clampSize(size)));
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(result), "Subcategories retrieved successfully"));
    }

    @PostMapping(value = "/subcategories", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SubcategoryResponseDto>> createSubcategory(
            @Valid @ModelAttribute SubcategoryRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                subcategoryService.createSubcategory(request), "Subcategory created successfully"));
    }

    @GetMapping("/subcategories/{id}")
    public ResponseEntity<ApiResponse<SubcategoryResponseDto>> getSubcategory(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                subcategoryService.getSubcategoryById(id), "Subcategory retrieved successfully"));
    }

    @PutMapping(value = "/subcategories/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SubcategoryResponseDto>> updateSubcategory(
            @PathVariable UUID id, @Valid @ModelAttribute SubcategoryRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                subcategoryService.updateSubcategory(id, request), "Subcategory updated successfully"));
    }

    @PatchMapping("/subcategories/{id}/status")
    public ResponseEntity<ApiResponse<SubcategoryResponseDto>> setSubcategoryStatus(
            @PathVariable UUID id, @RequestParam boolean active) {
        return ResponseEntity.ok(ApiResponse.success(
                subcategoryService.setActive(id, active),
                active ? "Subcategory activated successfully" : "Subcategory deactivated successfully"));
    }

    /**
     * Moves one subcategory within its own category's full list (active and inactive). Body: exactly
     * one of {@code afterId} or {@code position} (1 = top; past the end = bottom).
     */
    @PatchMapping("/subcategories/{id}/move")
    public ResponseEntity<ApiResponse<CatalogMoveResultDto>> moveSubcategory(
            @PathVariable UUID id, @Valid @RequestBody CatalogMoveRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                subcategoryService.moveSubcategory(id, request), "Subcategory moved successfully"));
    }

    // ── PRODUCTS ─────────────────────────────────────────────────────────

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<Page<ProductResponseDto>>> searchProducts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID subcategoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.success(
                adminProductService.searchProducts(search, categoryId, subcategoryId, pageable),
                "Products retrieved successfully"));
    }

    /** Requires at least one image in the "images" part -- see ProductCreateRequestDto. */
    @PostMapping(value = "/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProductResponseDto>> createProduct(
            @Valid @ModelAttribute ProductCreateRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                adminProductService.createProduct(request), "Product created successfully"));
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponseDto>> getProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                adminProductService.getProductById(id), "Product retrieved successfully"));
    }

    /** Text/pricing fields only -- JSON body. Images are managed via the endpoints below. */
    @PutMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponseDto>> updateProduct(
            @PathVariable UUID id, @Valid @RequestBody ProductRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                adminProductService.updateProduct(id, request), "Product updated successfully"));
    }

    @PatchMapping("/products/{id}/status")
    public ResponseEntity<ApiResponse<ProductResponseDto>> setProductStatus(
            @PathVariable UUID id, @RequestParam boolean active) {
        return ResponseEntity.ok(ApiResponse.success(
                adminProductService.setActive(id, active),
                active ? "Product activated successfully" : "Product deactivated successfully"));
    }

    // ── PRODUCT IMAGES ───────────────────────────────────────────────────

    @GetMapping("/products/{id}/images")
    public ResponseEntity<ApiResponse<List<ProductImageResponseDto>>> getProductImages(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                adminProductService.getImages(id), "Product images retrieved successfully"));
    }

    @PostMapping(value = "/products/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<List<ProductImageResponseDto>>> addProductImages(
            @PathVariable UUID id, @RequestParam("images") List<MultipartFile> images) {
        return ResponseEntity.ok(ApiResponse.success(
                adminProductService.addImages(id, images), "Images added successfully"));
    }

    @DeleteMapping("/products/{id}/images/{imageId}")
    public ResponseEntity<ApiResponse<List<ProductImageResponseDto>>> deleteProductImage(
            @PathVariable UUID id, @PathVariable UUID imageId) {
        return ResponseEntity.ok(ApiResponse.success(
                adminProductService.deleteImage(id, imageId), "Image deleted successfully"));
    }

    /** Full reorder -- body must list every image currently on the product. Position 0 = new cover. */
    @PutMapping("/products/{id}/images/reorder")
    public ResponseEntity<ApiResponse<List<ProductImageResponseDto>>> reorderProductImages(
            @PathVariable UUID id, @Valid @RequestBody ProductImageReorderRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success(
                adminProductService.reorderImages(id, request.getImageIds()), "Images reordered successfully"));
    }

    // ── COMBINED TREE (NEW) ──────────────────────────────────────────────

    /** One category, its subcategories, and each subcategory's products, all nested. No filtering. */
    @GetMapping("/categories/{id}/tree")
    public ResponseEntity<ApiResponse<CategoryTreeDto>> getCategoryTree(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                catalogTreeService.getCategoryTree(id), "Category tree retrieved successfully"));
    }

    /** Entire catalog, same nesting. Testing/tooling use -- cost scales with catalog size. */
    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<List<CategoryTreeDto>>> getFullCatalogTree() {
        return ResponseEntity.ok(ApiResponse.success(
                catalogTreeService.getFullCatalogTree(), "Full catalog tree retrieved successfully"));
    }

    // ── HELPERS ──────────────────────────────────────────────────────────

    /** status filter for /search: all (default) -> null, active -> TRUE, inactive -> FALSE. Case-insensitive. */
    private static Boolean parseStatus(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) {
            return null;
        }
        if (status.equalsIgnoreCase("active")) {
            return Boolean.TRUE;
        }
        if (status.equalsIgnoreCase("inactive")) {
            return Boolean.FALSE;
        }
        throw new BusinessException("CATALOG_INVALID_STATUS_FILTER",
                "status must be one of: all, active, inactive", HttpStatus.BAD_REQUEST);
    }

    /** Page size for /search is clamped to 1..100 per call. */
    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
