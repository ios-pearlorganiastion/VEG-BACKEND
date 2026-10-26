package com.veggofresh.admin.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponseDto {
    private UUID id;
    private String name;
    private String description;
    private UUID categoryId;
    private String categoryName;
    private UUID subcategoryId;
    private String subcategoryName;
    private BigDecimal price;

    /** null when there's no active discount -- see AdminProductServiceImpl.toDto(). */
    private BigDecimal originalPrice;
    /** What the vendor is paid per unit (admin-only; never copied into customer responses). */
    private BigDecimal vendorPrice;
    /** price - vendorPrice: the platform margin per unit. */
    private BigDecimal margin;

    private String unit;

    /**
     * ALWAYS computed, never stored -- round((originalPrice - price) / originalPrice * 100).
     * null whenever originalPrice is null (no discount). Computed exactly once,
     * here in Admin's own mapping, so every downstream consumer (Vendor's
     * ProductDto/VendorListingDto, Customer's browse screens) just copies this
     * value across rather than recomputing it themselves.
     */
    private Integer discountPercent;

    /**
     * The cover image (first by sortOrder in catalog_product_images) -- kept for
     * backward compatibility with every existing consumer that only ever expected a
     * single thumbnail (Vendor's ProductDto/VendorListingDto, Customer's browse
     * screens). No longer backed by a plain string column; derived from the same
     * data as {@link #imageUrls} below.
     */
    private String imageUrl;

    /** Full ordered gallery -- all of the product's images, cover first. */
    private List<String> imageUrls;

    private boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
}
