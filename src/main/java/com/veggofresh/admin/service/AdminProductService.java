package com.veggofresh.admin.service;

import com.veggofresh.admin.dto.request.ProductCreateRequestDto;
import com.veggofresh.admin.dto.request.ProductRequestDto;
import com.veggofresh.admin.dto.response.ProductImageResponseDto;
import com.veggofresh.admin.dto.response.ProductResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface AdminProductService {

    /** Multipart create -- requires at least one image (see ProductCreateRequestDto). */
    ProductResponseDto createProduct(ProductCreateRequestDto request);

    /** Text/pricing fields only. Images are managed exclusively through the methods below. */
    ProductResponseDto updateProduct(UUID id, ProductRequestDto request);

    ProductResponseDto getProductById(UUID id);
    java.util.Optional<ProductResponseDto> findProductById(UUID id);

    /**
     * Vendor price per product id (what the vendor is paid per unit). Ids that do not exist are simply absent
     * from the result. Used at checkout to snapshot the vendor price onto order items.
     */
    java.util.Map<UUID, java.math.BigDecimal> getVendorPrices(java.util.Collection<UUID> productIds);
    Page<ProductResponseDto> searchProducts(String search, UUID categoryId, UUID subcategoryId, Pageable pageable);
    ProductResponseDto setActive(UUID id, boolean active);

    // ── Product image management ────────────────────────────────────────

    List<ProductImageResponseDto> getImages(UUID productId);

    /** Adds one or more new images to the product, appended after the current ones. */
    List<ProductImageResponseDto> addImages(UUID productId, List<MultipartFile> images);

    /**
     * Removes one image. Refuses if this is the product's last remaining image --
     * every product must always have at least one.
     */
    List<ProductImageResponseDto> deleteImage(UUID productId, UUID imageId);

    /**
     * Full reorder: {@code imageIds} must list every image currently on the product,
     * in the new desired order. Position 0 becomes the new cover image.
     */
    List<ProductImageResponseDto> reorderImages(UUID productId, List<UUID> imageIds);
}
