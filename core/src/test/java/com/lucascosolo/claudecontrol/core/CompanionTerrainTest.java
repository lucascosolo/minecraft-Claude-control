package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class CompanionTerrainTest {
    private CompanionPlan.WalkCell cell(int x, int y, int z) {
        return new CompanionPlan.WalkCell(x, y, z);
    }

    private void assertSafePath(CompanionPlan.WalkCell start, CompanionPlan.WalkCell target,
            List<CompanionPlan.WalkCell> path, Predicate<CompanionPlan.WalkCell> standing,
            Predicate<CompanionPlan.WalkCell> clearance) {
        assertFalse(path.isEmpty(), "reachable safe terrain must have a route");
        assertEquals(target, path.getLast());
        assertFalse(path.contains(start), "movement route excludes its current position");
        assertTrue(path.size() <= 64);
        assertEquals(path.size(), new HashSet<>(path).size(), "route cannot revisit cells");
        var previous = start;
        for (var step : path) {
            assertTrue(standing.test(step), "every destination must allow safe standing");
            assertEquals(1, Math.abs(step.x() - previous.x()) + Math.abs(step.z() - previous.z()));
            assertTrue(Math.abs(step.y() - previous.y()) <= 1, "step height is at most one block");
            assertTrue(Math.abs(step.x() - start.x()) <= 16 && Math.abs(step.z() - start.z()) <= 16);
            assertTrue(Math.abs(step.y() - start.y()) <= 8);
            if (step.y() > previous.y()) {
                assertTrue(clearance.test(cell(previous.x(), previous.y() + 2, previous.z())),
                    "a rising step needs clearance above its departure");
                assertTrue(clearance.test(cell(step.x(), step.y() + 2, step.z())),
                    "a rising step needs clearance above its destination");
            } else if (step.y() < previous.y()) {
                assertTrue(clearance.test(cell(step.x(), previous.y() + 1, step.z())),
                    "a descending step needs clearance at the departure head height");
            }
            previous = step;
        }
    }

    @Test void climbsStairsAndChecksDepartureJumpHeadroom() {
        var start = cell(40, 70, -20);
        var middle = cell(41, 71, -20);
        var target = cell(42, 72, -20);
        Set<CompanionPlan.WalkCell> stairs = Set.of(start, middle, target);
        Set<CompanionPlan.WalkCell> queriedClearance = new HashSet<>();
        var path = CompanionPlan.terrainRoute(start, target, stairs::contains, c -> {
            queriedClearance.add(c);
            return true;
        });
        assertSafePath(start, target, path, stairs::contains, c -> true);
        assertEquals(List.of(middle, target), path);
        assertTrue(queriedClearance.contains(cell(40, 72, -20)));
        assertTrue(queriedClearance.contains(cell(41, 73, -20)));
        assertTrue(queriedClearance.contains(cell(42, 74, -20)));
    }

    @Test void descendsOneBlockAtATime() {
        var start = cell(0, 80, 0);
        var middle = cell(1, 79, 0);
        var target = cell(2, 78, 0);
        Set<CompanionPlan.WalkCell> stairs = Set.of(start, middle, target);
        var path = CompanionPlan.terrainRoute(start, target, stairs::contains, c -> true);
        assertSafePath(start, target, path, stairs::contains, c -> true);
        assertEquals(List.of(middle, target), path);
    }

    @Test void refusesRisingStepWhenDepartureHeadroomIsBlocked() {
        var start = cell(0, 64, 0);
        var target = cell(1, 65, 0);
        var obstructed = cell(0, 66, 0);
        assertTrue(CompanionPlan.terrainRoute(start, target, Set.of(start, target)::contains,
            c -> !c.equals(obstructed)).isEmpty());
    }

    @Test void refusesRisingStepWhenDestinationJumpHeadroomIsBlocked() {
        var start = cell(0, 64, 0);
        var target = cell(1, 65, 0);
        assertTrue(CompanionPlan.terrainRoute(start, target, Set.of(start, target)::contains,
            c -> !c.equals(cell(1, 67, 0))).isEmpty());
    }

    @Test void refusesDescendingStepWhenDestinationClearanceIsBlocked() {
        var start = cell(0, 64, 0);
        var target = cell(1, 63, 0);
        assertTrue(CompanionPlan.terrainRoute(start, target, Set.of(start, target)::contains,
            c -> !c.equals(cell(1, 65, 0))).isEmpty());
    }

    @Test void reachesHorizontalAndVerticalBoundaryThroughKnownSafeTerrain() {
        var start = cell(0, 64, 0);
        Set<CompanionPlan.WalkCell> flat = new HashSet<>();
        for (int x = 0; x <= 16; x++) flat.add(cell(x, 64, 0));
        var horizontalTarget = cell(16, 64, 0);
        assertSafePath(start, horizontalTarget,
            CompanionPlan.terrainRoute(start, horizontalTarget, flat::contains, c -> true),
            flat::contains, c -> true);
        Set<CompanionPlan.WalkCell> stairs = new HashSet<>();
        for (int x = 0; x <= 8; x++) stairs.add(cell(x, 64 + x, 0));
        var verticalTarget = cell(8, 72, 0);
        assertSafePath(start, verticalTarget,
            CompanionPlan.terrainRoute(start, verticalTarget, stairs::contains, c -> true),
            stairs::contains, c -> true);
    }

    @Test void refusesTwoBlockClimbsFallsAndDiagonalShortcuts() {
        var start = cell(0, 64, 0);
        for (var target : List.of(cell(1, 66, 0), cell(1, 62, 0), cell(1, 65, 1))) {
            assertTrue(CompanionPlan.terrainRoute(start, target, Set.of(start, target)::contains,
                c -> true).isEmpty());
        }
    }

    @Test void detoursOnlyThroughLoadedSafeStandingCells() {
        var start = cell(0, 64, 0);
        var target = cell(3, 64, 0);
        var hazard = cell(1, 64, 0);
        var unloaded = cell(1, 64, -1);
        Set<CompanionPlan.WalkCell> loadedSafe = Set.of(start, cell(0, 64, 1),
            cell(1, 64, 1), cell(2, 64, 1), cell(2, 64, 0), target);
        var path = CompanionPlan.terrainRoute(start, target, loadedSafe::contains, c -> true);
        assertSafePath(start, target, path, loadedSafe::contains, c -> true);
        assertEquals(5, path.size());
        assertFalse(path.contains(hazard));
        assertFalse(path.contains(unloaded));
    }

    @Test void refusesUnsafeStartUnsafeTargetAndDisconnectedSafeIslands() {
        var start = cell(0, 64, 0);
        var target = cell(4, 64, 0);
        for (Set<CompanionPlan.WalkCell> safe : List.of(Set.of(start), Set.of(target),
                Set.of(start, cell(1, 64, 0), target))) {
            assertTrue(CompanionPlan.terrainRoute(start, target, safe::contains, c -> true).isEmpty());
        }
    }

    @Test void movingTargetReplansFromCurrentTerrainPosition() {
        var start = cell(0, 64, 0);
        var first = cell(3, 64, 0);
        var second = cell(2, 65, 2);
        Set<CompanionPlan.WalkCell> safe = Set.of(start, cell(1, 64, 0), cell(2, 64, 0),
            first, cell(2, 65, 1), second);
        var original = CompanionPlan.terrainRoute(start, first, safe::contains, c -> true);
        assertSafePath(start, first, original, safe::contains, c -> true);
        var current = original.get(1);
        var revised = CompanionPlan.terrainRoute(current, second, safe::contains, c -> true);
        assertSafePath(current, second, revised, safe::contains, c -> true);
        assertNotEquals(original.getLast(), revised.getLast());
    }

    @Test void outsideHorizontalOrVerticalBoundsRefusesBeforeWorldQueries() {
        var start = cell(50, 70, -50);
        Predicate<CompanionPlan.WalkCell> unexpected = c -> {
            fail("out-of-range target must be refused before any world query: " + c);
            return true;
        };
        for (var target : List.of(cell(67, 70, -50), cell(33, 70, -50),
                cell(50, 70, -33), cell(50, 70, -67), cell(50, 79, -50),
                cell(50, 61, -50), cell(Integer.MIN_VALUE, 70, -50))) {
            assertTrue(CompanionPlan.terrainRoute(start, target, unexpected, unexpected).isEmpty());
        }
    }

    @Test void unsuccessfulSearchBoundsEachPredicatesUniqueInspections() {
        var start = cell(0, 64, 0);
        var target = cell(12, 64, 12);
        Set<CompanionPlan.WalkCell> inspectedStanding = new HashSet<>();
        Set<CompanionPlan.WalkCell> inspectedClearance = new HashSet<>();
        var path = CompanionPlan.terrainRoute(start, target, c -> {
            inspectedStanding.add(c);
            assertTrue(inspectedStanding.size() <= 256, "standing budget exceeded");
            assertTrue(Math.abs(c.x()) <= 16 && Math.abs(c.z()) <= 16);
            assertTrue(Math.abs(c.y() - start.y()) <= 8);
            return c.equals(target) || Math.abs(c.x() - target.x()) + Math.abs(c.z() - target.z()) != 1;
        }, c -> {
            inspectedClearance.add(c);
            assertTrue(inspectedClearance.size() <= 256, "clearance budget exceeded");
            assertTrue(Math.abs(c.x()) <= 16 && Math.abs(c.z()) <= 16);
            return true;
        });
        assertTrue(path.isEmpty());
        assertFalse(inspectedStanding.isEmpty());
        assertFalse(inspectedClearance.isEmpty(), "search explores rising alternatives");
    }

    @Test void longWindingRouteDoesNotReturnPartialMovement() {
        Set<CompanionPlan.WalkCell> safe = new HashSet<>();
        for (int row = 0; row < 7; row++) {
            for (int x = 0; x <= 8; x++) safe.add(cell(x, 64, row * 2));
            if (row < 6) safe.add(cell(row % 2 == 0 ? 8 : 0, 64, row * 2 + 1));
        }
        var start = cell(0, 64, 0);
        var target = cell(8, 64, 12);
        assertTrue(safe.contains(target));
        assertTrue(CompanionPlan.terrainRoute(start, target, safe::contains, c -> true).isEmpty());
    }

    @Test void alreadyAtGoalRequiresNoMovement() {
        var same = cell(5, 64, 5);
        assertTrue(CompanionPlan.terrainRoute(same, same, c -> true, c -> true).isEmpty());
    }
}
