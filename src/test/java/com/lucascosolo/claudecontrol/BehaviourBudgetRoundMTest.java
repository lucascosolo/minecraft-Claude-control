package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BehaviourBudgetRoundMTest {
    @Test void freshQuotaUsesNothing() { assertEquals(0, new BehaviourBudget.CoverQuota().used()); }

    @Test void takeAddsToUsed() {
        var q = new BehaviourBudget.CoverQuota();
        assertNull(q.take(0, 3));
        assertEquals(3, q.used());
    }

    @Test void restoredUsageCountsTowardTheCap() {
        var q = new BehaviourBudget.CoverQuota(BehaviourBudget.COVER_BLOCKS - 1);
        assertEquals(BehaviourBudget.COVER_BLOCKS - 1, q.used());
        var refused = q.take(0, 2);
        assertNotNull(refused);
        assertTrue(refused.contains("cover blocks"), refused);
        assertNull(q.take(0, 1));
        assertEquals(BehaviourBudget.COVER_BLOCKS, q.used());
    }
}
