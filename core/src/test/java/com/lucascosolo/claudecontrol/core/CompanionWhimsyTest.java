package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanionWhimsyTest {
    @Test void invitedIdleNearbyCompanionCanExpressAfterCooldown() {
        assertTrue(CompanionWhimsy.allowed(true,false,false,false,false,4,25_000,0));
        assertTrue(CompanionWhimsy.allowed(true,false,false,false,false,64,26_000,1_000));
    }
    @Test void stayPauseBusyWorkMovementAndMovingFriendEachPreventExpression() {
        assertFalse(CompanionWhimsy.allowed(false,false,false,false,false,4,30_000,0));
        assertFalse(CompanionWhimsy.allowed(true,true,false,false,false,4,30_000,0));
        // Listening, design and physical jobs share the busy admission flag.
        assertFalse(CompanionWhimsy.allowed(true,false,true,false,false,4,30_000,0));
        // Walking and jumping share the moving admission flag.
        assertFalse(CompanionWhimsy.allowed(true,false,false,true,false,4,30_000,0));
        assertFalse(CompanionWhimsy.allowed(true,false,false,false,true,4,30_000,0));
    }
    @Test void cooldownBoundaryIsExactAndBackwardTimeFailsClosed() {
        assertFalse(CompanionWhimsy.allowed(true,false,false,false,false,4,24_999,0));
        assertTrue(CompanionWhimsy.allowed(true,false,false,false,false,4,25_000,0));
        assertFalse(CompanionWhimsy.allowed(true,false,false,false,false,4,9_999,10_000));
    }
    @Test void proximityRequiresFiniteNonnegativeDistanceWithinEightBlocks() {
        assertTrue(CompanionWhimsy.allowed(true,false,false,false,false,0,25_000,0));
        assertTrue(CompanionWhimsy.allowed(true,false,false,false,false,64,25_000,0));
        for(double distance:List.of(-1.0,64.01,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            assertFalse(CompanionWhimsy.allowed(true,false,false,false,false,distance,25_000,0));
        }
    }
}
