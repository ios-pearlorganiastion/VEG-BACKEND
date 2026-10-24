package com.veggofresh.admin.service.impl;

import com.veggofresh.admin.dto.response.CatalogMoveResultDto;
import com.veggofresh.platform.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single home for catalog (category / subcategory) ordering rules, shared by
 * CatalogCategoryServiceImpl and CatalogSubcategoryServiceImpl.
 *
 * <ul>
 *   <li>One sort everywhere: display order, then name, then id. The name/id tie-breakers
 *       make the order deterministic, so paginated requests never repeat or skip an item.</li>
 *   <li>Pure move/renumber logic (no Spring, no database) so it is easy to unit test.</li>
 * </ul>
 */
final class CatalogOrdering {

    private CatalogOrdering() {
    }

    /** The one and only catalog sort. */
    static final Sort BY_DISPLAY_ORDER = Sort.by(
            Sort.Order.asc("displayOrder"),
            Sort.Order.asc("name"),
            Sort.Order.asc("id"));

    /**
     * Keeps the caller's page number/size but REPLACES any sort they passed with
     * {@link #BY_DISPLAY_ORDER}. Applied inside the paginated search methods so the admin,
     * vendor and customer endpoints can never disagree about order.
     */
    static Pageable orderedPage(Pageable requested) {
        if (requested == null || requested.isUnpaged()) {
            return Pageable.unpaged(BY_DISPLAY_ORDER);
        }
        return PageRequest.of(requested.getPageNumber(), requested.getPageSize(), BY_DISPLAY_ORDER);
    }

    /**
     * Returns the new full ordering after moving {@code movedId}.
     *
     * @param currentOrder ids of the whole list (all items in scope, active and inactive), in current order
     * @param afterId      place directly after this item, or null
     * @param position     1-based target position (past the end = bottom), or null
     *                     (exactly one of afterId / position must be given)
     */
    static List<UUID> move(List<UUID> currentOrder, UUID movedId, UUID afterId, Integer position) {
        if ((afterId == null) == (position == null)) {
            throw invalid("Provide exactly one of 'afterId' or 'position'");
        }
        if (position != null && position < 1) {
            throw invalid("position must be 1 or greater");
        }
        if (!currentOrder.contains(movedId)) {
            throw new BusinessException("CATALOG_MOVE_ITEM_NOT_FOUND",
                    "Item to move was not found in this list", HttpStatus.NOT_FOUND);
        }

        List<UUID> rest = new ArrayList<>(currentOrder);
        rest.remove(movedId);

        int index;
        if (position != null) {
            index = Math.min(position - 1, rest.size());
        } else {
            if (afterId.equals(movedId)) {
                throw invalid("An item cannot be placed after itself");
            }
            int anchorIndex = rest.indexOf(afterId);
            if (anchorIndex < 0) {
                throw new BusinessException("CATALOG_MOVE_ANCHOR_NOT_FOUND",
                        "The 'afterId' item was not found in this list", HttpStatus.NOT_FOUND);
            }
            index = anchorIndex + 1;
        }

        rest.add(index, movedId);
        return rest;
    }

    /**
     * Compares stored positions with the desired order (1..n) and returns ONLY the rows
     * whose stored value differs. Because it compares against the stored value (not just
     * "who was shifted"), it also repairs ties / gaps left by older data.
     *
     * @param storedOrder id -> currently stored display_order
     * @param newOrder    ids in the desired order
     */
    static List<CatalogMoveResultDto.Change> diff(Map<UUID, Integer> storedOrder, List<UUID> newOrder) {
        List<CatalogMoveResultDto.Change> changes = new ArrayList<>();
        for (int i = 0; i < newOrder.size(); i++) {
            UUID id = newOrder.get(i);
            int expected = i + 1;
            Integer stored = storedOrder.get(id);
            if (stored == null || stored != expected) {
                changes.add(new CatalogMoveResultDto.Change(id, expected));
            }
        }
        return changes;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("CATALOG_MOVE_INVALID", message, HttpStatus.BAD_REQUEST);
    }
}
