package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class CompanionPlanTest {
    @Test void companionshipReplansWhenPlayerMovesAndAvoidsTheirOccupiedCell() {
        var start=new CompanionPlan.Cell(0,0);
        var first=new CompanionPlan.Cell(8,0);
        var second=new CompanionPlan.Cell(8,6);
        var original=CompanionPlan.companyRoute(start,first,c->true);
        assertFalse(original.isEmpty());
        assertFalse(original.contains(first));
        var moved=CompanionPlan.companyRoute(original.get(1),second,c->true);
        assertFalse(moved.isEmpty());
        assertFalse(moved.contains(second));
        var destination=moved.getLast();
        assertEquals(2,Math.abs(destination.x()-second.x())+Math.abs(destination.z()-second.z()));
        assertNotEquals(original.getLast(),destination);
    }

    @Test void companionshipRetriesAfterBlockedTerrainBecomesWalkable() {
        var start=new CompanionPlan.Cell(0,0);var player=new CompanionPlan.Cell(8,0);
        assertTrue(CompanionPlan.companyRoute(start,player,c->c.equals(start)).isEmpty());
        assertFalse(CompanionPlan.companyRoute(start,player,c->true).isEmpty());
    }

    private CompanionPlan.Cell cell(int x, int z) { return new CompanionPlan.Cell(x, z); }

    private void assertSafePath(CompanionPlan.Cell start, CompanionPlan.Cell goal,
            List<CompanionPlan.Cell> path, Predicate<CompanionPlan.Cell> safe) {
        assertFalse(path.isEmpty(), "reachable safe goal must have a route");
        assertEquals(goal, path.get(path.size() - 1));
        assertTrue(path.size() <= 64);
        assertEquals(path.size(), new HashSet<>(path).size(), "route cannot revisit cells");
        CompanionPlan.Cell previous = start;
        for (CompanionPlan.Cell step : path) {
            assertTrue(safe.test(step), "every step must be loaded and walkable");
            assertEquals(1, Math.abs(step.x() - previous.x()) + Math.abs(step.z() - previous.z()),
                "companion may move only one cardinal cell per step");
            assertTrue(Math.abs(step.x() - start.x()) <= 16 && Math.abs(step.z() - start.z()) <= 16);
            previous = step;
        }
    }

    @Test void reachesCardinalBoundaryThroughKnownSafeCorridor() {
        CompanionPlan.Cell start = cell(50, -50), target = cell(66, -50);
        Predicate<CompanionPlan.Cell> corridor = c -> c.z() == -50 && c.x() >= 50 && c.x() <= 66;
        List<CompanionPlan.Cell> path = CompanionPlan.route(start, target, corridor);
        assertSafePath(start, target, path, corridor);
        assertEquals(16, path.size());
        assertFalse(path.contains(start), "movement plan excludes current position");
    }

    @Test void detoursAroundHazardAndUnloadedShortcutWithoutDiagonalMovement() {
        CompanionPlan.Cell start = cell(0, 0), target = cell(3, 0);
        CompanionPlan.Cell hazard = cell(1, 0), unloaded = cell(1, -1);
        Set<CompanionPlan.Cell> safe = Set.of(start, cell(0, 1), cell(1, 1), cell(2, 1), cell(2, 0), target);
        List<CompanionPlan.Cell> path = CompanionPlan.route(start, target, safe::contains);
        assertSafePath(start, target, path, safe::contains);
        assertEquals(5, path.size(), "known safe detour is the only cardinal route");
        assertFalse(path.contains(hazard));
        assertFalse(path.contains(unloaded));
    }

    @Test void diagonalGoalCannotBeReachedThroughBlockedCardinalNeighbors() {
        CompanionPlan.Cell start = cell(0, 0), target = cell(1, 1);
        assertTrue(CompanionPlan.route(start, target, Set.of(start, target)::contains).isEmpty());
    }

    @Test void blockedOrDisconnectedGoalHasNoRoute() {
        CompanionPlan.Cell start = cell(0, 0), target = cell(4, 0);
        assertTrue(CompanionPlan.route(start, target, c -> c.equals(start)).isEmpty());
        Set<CompanionPlan.Cell> islands = Set.of(start, cell(1, 0), target, cell(4, 1));
        assertTrue(CompanionPlan.route(start, target, islands::contains).isEmpty());
    }

    @Test void outsideRadiusRefusesBeforeInspectingWorld() {
        AtomicInteger inspected = new AtomicInteger();
        CompanionPlan.Cell start = cell(0, 0);
        for (CompanionPlan.Cell target : List.of(cell(17, 0), cell(-17, 0), cell(0, 17), cell(0, -17))) {
            assertTrue(CompanionPlan.route(start, target, c -> { inspected.incrementAndGet(); return true; }).isEmpty());
        }
        assertEquals(0, inspected.get(), "out-of-range requests must not inspect world cells");
    }

    @Test void unsuccessfulSearchHasBoundedUniqueCellInspectionsInsideRadius() {
        CompanionPlan.Cell start = cell(0, 0), target = cell(12, 12);
        Set<CompanionPlan.Cell> inspected = new HashSet<>();
        List<CompanionPlan.Cell> path = CompanionPlan.route(start, target, c -> {
            inspected.add(c);
            assertTrue(inspected.size() <= 256, "search must stop before exceeding its inspection budget");
            assertTrue(Math.abs(c.x()) <= 16 && Math.abs(c.z()) <= 16, "no world reads outside radius");
            // Goal is safe, but a closed ring makes it unreachable from the open region.
            return c.equals(target) || Math.abs(c.x() - target.x()) + Math.abs(c.z() - target.z()) != 1;
        });
        assertTrue(path.isEmpty());
        assertFalse(inspected.isEmpty());
        assertTrue(inspected.size() <= 256);
    }

    @Test void refusesLongWindingRouteRatherThanReturningPartialMovement() {
        Set<CompanionPlan.Cell> corridor = new HashSet<>();
        for (int row = 0; row < 7; row++) {
            for (int x = 0; x <= 8; x++) corridor.add(cell(x, row * 2));
            if (row < 6) corridor.add(cell(row % 2 == 0 ? 8 : 0, row * 2 + 1));
        }
        CompanionPlan.Cell start = cell(0, 0), target = cell(8, 12);
        assertTrue(corridor.contains(target));
        assertTrue(CompanionPlan.route(start, target, corridor::contains).isEmpty(),
            "only route exceeds 64 steps; truncation would falsely claim arrival");
    }

    @Test void alreadyAtGoalRequiresNoMovement() {
        CompanionPlan.Cell same = cell(5, 5);
        assertTrue(CompanionPlan.route(same, same, c -> true).isEmpty());
    }
}
