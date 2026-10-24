package com.veggofresh.admin.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Result of a catalog move. {@code changed} lists every item whose stored position was
 * rewritten (including the moved item itself, unless it was already in that place) so the
 * admin panel can patch its local list without reloading. An empty {@code changed} means
 * nothing needed to move.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogMoveResultDto {

    private UUID movedId;

    /** New 1-based position of the moved item. */
    private int displayOrder;

    private List<Change> changed;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Change {
        private UUID id;
        private int displayOrder;
    }
}
