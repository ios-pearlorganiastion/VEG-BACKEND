package com.veggofresh.admin.entity;

import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Admin-owned master catalog product. NOT the same as Vendor's pre-pivot
 * com.veggofresh.vendor.entity.Product (legacy, vendor-owned) — see
 * NOTES_ADMIN.md, "Class-name collision with Vendor module."
 */
@Entity
@Table(name = "catalog_products")
@Getter
@Setter
@NoArgsConstructor
public class CatalogProduct extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CatalogCategory category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subcategory_id", nullable = false)
    private CatalogSubcategory subcategory;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /**
     * What the VENDOR is paid per unit. Never shown to customers. price - vendorPrice is the platform's
     * margin, which funds coupon discounts. Always > 0 and <= price (also enforced by a DB check, V167).
     */
    @Column(name = "vendor_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal vendorPrice;

    /**
     * Optional "was" price for showing a strikethrough + discount badge on
     * browse screens. null = no discount, show price only. When set, must be
     * strictly greater than price (enforced in AdminProductServiceImpl, not
     * here, so the same rule applies consistently on both create and update).
     * discountPercent is NEVER stored -- always computed fresh from
     * originalPrice vs price in AdminProductServiceImpl.toDto(), so it can
     * never drift out of sync with a price edit.
     */
    @Column(name = "original_price", precision = 10, scale = 2)
    private BigDecimal originalPrice;

    /**
     * Free-text pack-size label shown next to quantity on cart/order lines,
     * e.g. "1 kg", "6 pcs", "2 pcs (1 kg each)". Deliberately NOT a
     * structured {value, unit-enum} pair -- nothing in the system ever
     * parses this string, it's pure display text riding alongside quantity,
     * so free text covers any pack description without needing its own
     * variant/weight-based-pricing model.
     */
    @Column(length = 100)
    private String unit;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;
}
