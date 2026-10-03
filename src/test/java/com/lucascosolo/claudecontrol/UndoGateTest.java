package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

// static String UndoGate.refusal(boolean owner, boolean wakeOnly, List<String> turnActors, List<String> entryPlayers, String entryOrigin)  null = allowed
class UndoGateTest {
    private static final List<String> NONE = List.of();

    @Test void wakeOnlyRefusedEvenForOwner() {
        assertTrue(UndoGate.refusal(true, true, List.of("Sam"), List.of("Sam"), "turn").contains("wake"));
    }

    @Test void ownerAllowedForAnyOrigin() {
        for (String o : List.of("turn", "later", "routine", "wake", "event", "unknown"))
            assertNull(UndoGate.refusal(true, false, NONE, NONE, o), o);
    }

    @Test void nonTurnOriginNeedsOwner() {
        for (String o : List.of("later", "wake", "event", "unknown"))
            assertTrue(UndoGate.refusal(false, false, List.of("Sam"), List.of("Sam"), o).contains("owner"), o);
    }

    @Test void entryWithoutPlayersNeedsOwner() {
        assertTrue(UndoGate.refusal(false, false, List.of("Sam"), NONE, "turn").contains("owner"));
    }

    @Test void allEntryPlayersPresentIsAllowedCaseInsensitively() {
        assertNull(UndoGate.refusal(false, false, List.of("SAM", "alex", "Kit"), List.of("Sam", "Alex"), "turn"));
    }

    @Test void missingPlayerRefusedAndNamed() {
        String r = UndoGate.refusal(false, false, List.of("Sam"), List.of("Sam", "Alex"), "turn");
        assertNotNull(r);
        assertTrue(r.contains("Alex"));
    }

    @Test void repairEntryNamesRepairReport() {
        String r = UndoGate.refusal(false, false, List.of("Sam"), List.of("Sam"), "repair");
        assertNotNull(r);
        assertTrue(r.contains("a repair report"), r);
    }
}
