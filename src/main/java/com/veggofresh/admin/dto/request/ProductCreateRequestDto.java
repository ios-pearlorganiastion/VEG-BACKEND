package com.veggofresh.admin.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Used for {@code POST /api/admin/catalog/products} only. Bound via
 * {@code @ModelAttribute} from {@code multipart/form-data} so the initial batch of
 * product images rides in the same call as the rest of the fields.
 *
 * <p>{@code images} is REQUIRED with at least one file -- a product cannot exist with
 * zero images (unlike Customer/Vendor/Delivery avatars, or the Category/Subcategory
 * icon, which are optional). There is no fixed upper limit; upload as many as you like
 * in one call, or add more later via {@code POST /products/{id}/images}.
 */
@Getter
@Setter
public class ProductCreateRequestDto {

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

    /** At least one image is required. Position in this list becomes the initial sort order (first = cover). */
    private List<MultipartFile> images;
}
