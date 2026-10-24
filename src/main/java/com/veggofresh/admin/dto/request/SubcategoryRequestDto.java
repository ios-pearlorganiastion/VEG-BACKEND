package com.veggofresh.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Bound via {@code @ModelAttribute} from {@code multipart/form-data} (not JSON) so the
 * subcategory image rides in the same call as the rest of the fields, for both create
 * and update. {@code image} is a new capability -- subcategories had no image field at
 * all before this. Optional and single: omit it to leave the existing image unchanged
 * (on update) or create with no image (on create); the old Cloudinary asset is
 * auto-deleted on replace.
 *
 * <p>There is deliberately NO {@code displayOrder} here. A new subcategory is appended to
 * the bottom of its category by the server, and the order is changed only through
 * {@code PATCH /api/admin/catalog/subcategories/{id}/move}. A {@code displayOrder} field
 * sent by an older client is simply ignored.
 */
@Getter
@Setter
public class SubcategoryRequestDto {

    @NotNull(message = "categoryId is required")
    private UUID categoryId;

    @NotBlank(message = "Subcategory name is required")
    @Size(max = 150)
    private String name;

    /** Optional subcategory icon/image (jpg/jpeg/png/webp). Omit to leave unchanged. */
    private MultipartFile image;
}
