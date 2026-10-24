package com.veggofresh.admin.repository;

import java.util.UUID;

/**
 * Lightweight (id + stored position) projection used by the move logic, so reordering a
 * list of hundreds/thousands of categories never loads full entities just to compute the
 * new order. Only the rows whose position actually changes are loaded and written.
 */
public interface CatalogOrderRow {
    UUID getId();
    int getDisplayOrder();
}
