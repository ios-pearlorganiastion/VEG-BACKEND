package com.veggofresh.admin.dto.request;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * JSON body for {@code PATCH /api/admin/catalog/categories/{id}/move} and
 * {@code PATCH /api/admin/catalog/subcategories/{id}/move}.
 *
 * <p>Send <b>exactly one</b> of:
 * <ul>
 *   <li>{@code afterId} -- place the item directly after this item (drag-and-drop: the item
 *       the admin dropped it under). The anchor must be in the same list (for subcategories,
 *       the same category) and cannot be the item being moved.</li>
 *   <li>{@code position} -- 1-based position in the full list (1 = top). A value past the
 *       end puts the item at the bottom.</li>
 * </ul>
 * To move an item to the very top, send {@code { "position": 1 }}.
 */
@Getter
@Setter
public class CatalogMoveRequestDto {

    private UUID afterId;

    @Min(value = 1, message = "position must be 1 or greater")
    private Integer position;
}
