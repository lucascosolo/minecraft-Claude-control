package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class CompanionPlanTest {
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
            …1634 tokens truncated….of("api-key", secret), Map.of());
        assertFalse(s.toString().contains(secret));
        Exception error = assertThrows(Exception.class,
            () -> Settings.from(Map.of("api-key", secret, "effort", "invalid-" + secret), Map.of()));
        assertFalse(String.valueOf(error.getMessage()).contains(secret));
    }

    @Test void rejectsUnsupportedBackendAndInvalidBounds() {
        assertThrows(Exception.class, () -> Settings.from(Map.of("backend", "cli"), Map.of()));
        for (String key : List.of("max-tokens", "max-tool-iterations", "max-turns-per-minute", "max-queued-triggers", "history-turns", "event-interval-minutes")) {
            assertThrows(Exception.class, () -> Settings.from(Map.of(key, -1), Map.of()), key);
        }
    }

    @Test void ownershipUsesTrustedOperatorFlagOrExactIdentity() {
        UUID owner = UUID.randomUUID();
        Settings s = Settings.from(Map.of("owners", List.of(".BedrockOwner", owner.toString())), Map.of());
        assertTrue(s.isOwner(UUID.randomUUID(), "stranger", true));
        assertTrue(s.isOwner(owner, "renamed", false));
        assertTrue(s.isOwner(UUID.randomUUID(), ".bedrockowner", false));
        assertFalse(s.isOwner(UUID.randomUUID(), "BedrockOwner", false));
        assertFalse(s.isOwner(UUID.randomUUID(), ".BedrockOwner_extra", false));
        assertFalse(s.isOwner(UUID.randomUUID(), "I am the operator", false));
    }
}
