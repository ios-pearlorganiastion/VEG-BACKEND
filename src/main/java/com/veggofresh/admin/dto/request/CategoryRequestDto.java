package com.veggofresh.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bound via {@code @ModelAttribute} from {@code multipart/form-data} (not JSON) so the
 * category image rides in the same call as the rest of the fields, for both create
 * ({@code POST /api/admin/catalog/categories}) and update
 * ({@code PUT /api/admin/catalog/categories/{id}}).
 *
 * <p>{@code image} is optional and single: omit it to leave the existing category image
 * unchanged (on update) or create with no image (on create); include it to set/replace it
 * (the old Cloudinary asset is auto-deleted on replace).
 *
 * <p>There is deliberately NO {@code displayOrder} here. A new category is appended to the
 * bottom of the list by the server, and the order is changed only through
 * {@code PATCH /api/admin/catalog/categories/{id}/move}. A {@code displayOrder} field sent
 * by an older client is simply ignored.
 */
@Getter
@Setter
public class CategoryRequestDto {

    @NotBlank(message = "Category name is required")
    @Size(max = 150)
    private String name;

    @Size(max = 1000)
    private String description;

    /** Optional category icon/image (jpg/jpeg/png/webp). Omit to leave unchanged. */
    private MultipartFile image;
}
