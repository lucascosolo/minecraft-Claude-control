package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

// Round K: spawned-mob cap and cover quota.
class BehaviourBudgetRoundKTest {
    @Test void spawnRoom() {
        assertEquals(200, BehaviourBudget.MAX_SPAWNED);
        assertTrue(BehaviourBudget.spawnRoom(190, 10));
        assertFalse(BehaviourBudget.spawnRoom(191, 10));
        assertTrue(BehaviourBudget.spawnRoom(0, 200));
    }

    @Test void coverConstants() {
        assertEquals(200, BehaviourBudget.COVER_GAP);
        assertEquals(2000, BehaviourBudget.COVER_BLOCKS);
    }

    @Test void coverQuotaGapAndTotal() {
        var q = new BehaviourBudget.CoverQuota();
        assertNull(q.take(0, 500));
        assertNotNull(q.take(100, 10));   // too soon; records nothing
        assertNull(q.take(200, 500));
        assertNull(q.take(400, 1000));    // total now 2000
        assertNotNull(q.take(600, 1));    // over the block total
    }

    @Test void refusedTakeRecordsNothing() {
        var q = new BehaviourBudget.CoverQuota();
        assertNull(q.take(0, 1500));
        assertNotNull(q.take(300, 600));  // over total, not recorded
        assertNull(q.take(300, 500));     // still fits, gap measured from tick 0
    }
}
