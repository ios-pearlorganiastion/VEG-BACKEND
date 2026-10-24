package com.veggofresh.admin.service.impl;

import com.veggofresh.admin.dto.response.CatalogMoveResultDto;
import com.veggofresh.platform.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the catalog move / renumber rules (no Spring, no database). */
class CatalogOrderingTest {

    private static final UUID A = id(1), B = id(2), C = id(3), D = id(4), E = id(5);
    private static final List<UUID> ABCDE = List.of(A, B, C, D, E);

    private static UUID id(int n) {
        return new UUID(0L, n);
    }

    private static Map<UUID, Integer> stored(int... values) {
        Map<UUID, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            map.put(id(i + 1), values[i]);
        }
        return map;
    }

    // ── position ─────────────────────────────────────────────────────────

    @Test
    void positionMovesItemUpAndShiftsOthersDown() {
        assertEquals(List.of(A, B, E, C, D), CatalogOrdering.move(ABCDE, E, null, 3));
    }

    @Test
    void positionMovesItemDownAndShiftsOthersUp() {
        assertEquals(List.of(A, C, D, E, B), CatalogOrdering.move(ABCDE, B, null, 5));
    }

    @Test
    void positionOneIsTheTop() {
        assertEquals(List.of(E, A, B, C, D), CatalogOrdering.move(ABCDE, E, null, 1));
    }

    @Test
    void positionPastTheEndMeansBottom() {
        assertEquals(List.of(B, C, D, E, A), CatalogOrdering.move(ABCDE, A, null, 99));
    }

    @Test
    void movingToItsOwnPositionChangesNothing() {
        assertEquals(ABCDE, CatalogOrdering.move(ABCDE, C, null, 3));
    }

    // ── afterId ──────────────────────────────────────────────────────────

    @Test
    void afterIdPlacesItemDirectlyAfterAnchor() {
        assertEquals(List.of(A, E, B, C, D), CatalogOrdering.move(ABCDE, E, A, null));
    }

    @Test
    void afterIdWorksMovingDown() {
        assertEquals(List.of(B, C, D, A, E), CatalogOrdering.move(ABCDE, A, D, null));
    }

    @Test
    void afterTheLastItemMeansBottom() {
        assertEquals(List.of(B, C, D, E, A), CatalogOrdering.move(ABCDE, A, E, null));
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    void afterItselfIsRejected() {
        assertThrows(BusinessException.class, () -> CatalogOrdering.move(ABCDE, C, C, null));
    }

    @Test
    void unknownAnchorIsRejected() {
        assertThrows(BusinessException.class, () -> CatalogOrdering.move(ABCDE, C, id(99), null));
    }

    @Test
    void unknownMovedItemIsRejected() {
        assertThrows(BusinessException.class, () -> CatalogOrdering.move(ABCDE, id(99), null, 2));
    }

    @Test
    void mustSendExactlyOneOfAfterIdOrPosition() {
        assertThrows(BusinessException.class, () -> CatalogOrdering.move(ABCDE, C, null, null));
        assertThrows(BusinessException.class, () -> CatalogOrdering.move(ABCDE, C, A, 2));
    }

    @Test
    void positionBelowOneIsRejected() {
        assertThrows(BusinessException.class, () -> CatalogOrdering.move(ABCDE, C, null, 0));
    }

    // ── diff (which rows get written) ────────────────────────────────────

    @Test
    void diffReturnsOnlyTheRowsThatChanged() {
        // E moved to position 2 in a clean 1..5 list: E, B, C, D change; A does not.
        List<UUID> moved = CatalogOrdering.move(ABCDE, E, A, null);
        List<CatalogMoveResultDto.Change> changes = CatalogOrdering.diff(stored(1, 2, 3, 4, 5), moved);

        assertEquals(4, changes.size());
        assertEquals(new CatalogMoveResultDto.Change(E, 2), changes.get(0));
        assertEquals(new CatalogMoveResultDto.Change(B, 3), changes.get(1));
        assertEquals(new CatalogMoveResultDto.Change(C, 4), changes.get(2));
        assertEquals(new CatalogMoveResultDto.Change(D, 5), changes.get(3));
    }

    @Test
    void diffIsEmptyWhenNothingMoved() {
        List<UUID> same = CatalogOrdering.move(ABCDE, C, null, 3);
        assertTrue(CatalogOrdering.diff(stored(1, 2, 3, 4, 5), same).isEmpty());
    }

    @Test
    void diffRepairsOldTiedDataOnFirstMove() {
        // Legacy data: every row stored as 0. Any move renumbers the whole list 1..n.
        List<CatalogMoveResultDto.Change> changes =
                CatalogOrdering.diff(stored(0, 0, 0, 0, 0), CatalogOrdering.move(ABCDE, E, null, 1));

        assertEquals(5, changes.size());
        assertEquals(new CatalogMoveResultDto.Change(E, 1), changes.get(0));
        assertEquals(new CatalogMoveResultDto.Change(A, 2), changes.get(1));
    }

    @Test
    void diffClosesGapsLeftByRemovedItems() {
        // stored 1,2,4,7,9 (gaps) -> renumbered 1..5 with the same order
        List<CatalogMoveResultDto.Change> changes =
                CatalogOrdering.diff(stored(1, 2, 4, 7, 9), ABCDE);

        assertEquals(3, changes.size());
        assertEquals(new CatalogMoveResultDto.Change(C, 3), changes.get(0));
        assertEquals(new CatalogMoveResultDto.Change(D, 4), changes.get(1));
        assertEquals(new CatalogMoveResultDto.Change(E, 5), changes.get(2));
    }
}
