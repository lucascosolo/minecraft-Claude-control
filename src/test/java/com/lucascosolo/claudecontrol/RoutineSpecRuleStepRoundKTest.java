package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

// Round K: spawn lifetime_seconds in rule steps.
class RoutineSpecRuleStepRoundKTest {
    private static RoutineSpec.Step spawn(String extra) {
        return RoutineSpec.ruleStep(j("'spawn'"), j("{'type':'cow','at':{'near':'self'}" + extra + "}"));
    }

    @Test void lifetimeDefaultsTo60() {
        assertEquals(60, spawn("").args().get("lifetime_seconds").asInt());
    }

    @Test void lifetimeBounds() {
        assertEquals(1, spawn(",'lifetime_seconds':1").args().get("lifetime_seconds").asInt());
        assertEquals(600, spawn(",'lifetime_seconds':600").args().get("lifetime_seconds").asInt());
        rejects(() -> spawn(",'lifetime_seconds':0"));
        rejects(() -> spawn(",'lifetime_seconds':601"));
    }

    @Test void countStillOneToTen() {
        assertNotNull(spawn(",'count':10"));
        rejects(() -> spawn(",'count':11"));
    }
}
