package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompanionPresenceTest {
    private static final long PREVIOUS = 100_000;
    private static final long READY = PREVIOUS + 15_000;

    @Test void distantBodyCatchesUpEvenWhenAWalkingRouteExists() {
        assertTrue(CompanionPresence.catchUp(true, 625, true, READY, PREVIOUS));
        assertTrue(CompanionPresence.catchUp(true, 625, false, READY, PREVIOUS));
        assertTrue(CompanionPresence.catchUp(true, Math.nextUp(576.0), true, READY, PREVIOUS));
    }

    @Test void crossWorldBodyCatchesUpEvenWithZeroReportedDistance() {
        assertTrue(CompanionPresence.catchUp(false, 0, true, READY, PREVIOUS));
        assertTrue(CompanionPresence.catchUp(false, 0, false, READY, PREVIOUS));
    }

    @Test void nearbyBodyNeverCatchesUpWithinThreeBlocks() {
        for (double distanceSquared : new double[] {0, 1, 8.99, 9}) {
            assertFalse(CompanionPresence.catchUp(true, distanceSquared, true, READY, PREVIOUS));
            assertFalse(CompanionPresence.catchUp(true, distanceSquared, false, READY, PREVIOUS));
        }
    }

    @Test void blockedRouteBeyondThreeBlocksAllowsCatchUp() {
        assertTrue(CompanionPresence.catchUp(true, Math.nextUp(9.0), false, READY, PREVIOUS));
        assertTrue(CompanionPresence.catchUp(true, 100, false, READY, PREVIOUS));
        assertTrue(CompanionPresence.catchUp(true, 576, false, READY, PREVIOUS));
    }

    @Test void safeRouteWithinTwentyFourBlocksPrefersWalking() {
        assertFalse(CompanionPresence.catchUp(true, Math.nextUp(9.0), true, READY, PREVIOUS));
        assertFalse(CompanionPresence.catchUp(true, 100, true, READY, PREVIOUS));
        assertFalse(CompanionPresence.catchUp(true, 576, true, READY, PREVIOUS));
    }

    @Test void invalidDistancesRefuseEvenForCrossWorldBodies() {
        for (double distanceSquared : new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            for (boolean sameWorld : new boolean[] {true, false}) {
                for (boolean routeFound : new boolean[] {true, false}) {
                    assertFalse(CompanionPresence.catchUp(sameWorld, distanceSquared,
                        routeFound, READY, PREVIOUS));
                }
            }
        }
    }

    @Test void cooldownAllowsExactlyFifteenSecondsThenDelaysRepeat() {
        assertFalse(CompanionPresence.catchUp(true, 625, true, READY - 1, PREVIOUS));
        assertFalse(CompanionPresence.catchUp(false, 0, false, READY - 1, PREVIOUS));
        assertTrue(CompanionPresence.catchUp(true, 625, true, READY, PREVIOUS));
        long recordedCatchUp = READY;
        assertFalse(CompanionPresence.catchUp(true, 625, true, recordedCatchUp, recordedCatchUp));
        assertFalse(CompanionPresence.catchUp(true, 625, true,
            recordedCatchUp + 14_999, recordedCatchUp));
        assertTrue(CompanionPresence.catchUp(true, 625, true,
            recordedCatchUp + 15_000, recordedCatchUp));
    }

    @Test void clockRollbackRefusesCatchUp() {
        assertFalse(CompanionPresence.catchUp(true, 625, false, PREVIOUS - 1, PREVIOUS));
        assertFalse(CompanionPresence.catchUp(false, 0, true, PREVIOUS - 1, PREVIOUS));
        assertFalse(CompanionPresence.catchUp(false, 0, false, Long.MIN_VALUE, Long.MAX_VALUE));
    }
}
