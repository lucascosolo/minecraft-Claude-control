package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

// D11: a routine's change can be reverted by the players who asked for the routine, like a turn's.
class UndoGateRoutineTest {
    @Test void allEntryPlayersAskingIsAllowedCaseInsensitively() {
        assertNull(UndoGate.refusal(false, false, List.of("sam", "Amy"), List.of("Sam"), "routine"));
    }

    @Test void missingPlayerIsNamed() {
        String r = UndoGate.refusal(false, false, List.of("Sam"), List.of("Sam", "Amy"), "routine");
        assertNotNull(r);
        assertTrue(r.contains("Amy"), r);
    }

    @Test void noRecordedPlayerNeedsAnOwner() {
        String r = UndoGate.refusal(false, false, List.of("Sam"), List.of(), "routine");
        assertNotNull(r);
        assertTrue(r.contains("owner"), r);
    }

    @Test void otherOriginsStillNeedAnOwner() {
        for (String o : List.of("later", "wake", "event")) {
            String r = UndoGate.refusal(false, false, List.of("Sam"), List.of("Sam"), o);
            assertNotNull(r, o);
            assertTrue(r.contains("owner"), r);
        }
    }
}
