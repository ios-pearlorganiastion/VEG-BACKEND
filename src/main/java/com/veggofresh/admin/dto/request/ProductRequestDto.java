package com.veggofresh.admin.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Used for {@code PUT /api/admin/catalog/products/{id}} only -- text/pricing fields.
 * Stays a plain JSON {@code @RequestBody}. Product images are managed exclusively
 * through the dedicated endpoints (add/delete/reorder), not through this DTO --
 * {@code imageUrl} has been removed for that reason. For the multipart create flow
 * (which requires at least one image up front), see {@link ProductCreateRequestDto}.
 */
@Getter
@Setter
public class ProductRequestDto {

    @NotBlank(message = "Product name is required")
    @Size(max = 200)
    private String name;

    @Size(max = 2000)
    private String description;

    @NotNull(message = "categoryId is required")
    private UUID categoryId;

    @NotNull(message = "subcategoryId is required")
    private UUID subcategoryId;

    @NotNull(message = "price is required")
    @DecimalMin(value = "0.01", message = "price must be greater than 0")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal price;

    /**
     * Optional "was" price for the strikethrough/discount badge. Omit or
     * leave null for no discount. If provided, must be strictly greater
     * than price -- validated in AdminProductServiceImpl (needs both fields
     * together, so it can't live as a simple per-field annotation here).
     */
    @DecimalMin(value = "0.01", message = "originalPrice must be greater than 0")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal originalPrice;

    /**
     * Optional. What the vendor is paid per unit (must be > 0 and not above price). On create, omitting it
     * means "same as price" (no margin). On update, omitting it keeps the current vendor price.
     */
    @DecimalMin(value = "0.01", message = "vendorPrice must be greater than 0")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal vendorPrice;

    @NotBlank(message = "unit is required, e.g. '1 kg', '6 pcs'")
    @Size(max = 100)
    private String unit;
}
