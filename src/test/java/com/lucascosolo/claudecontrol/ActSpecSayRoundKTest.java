package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Round K: dying-safe actions, say token substitution and say roles.
class ActSpecSayRoundKTest {
    @Test void dyingOkSet() {
        assertEquals(Set.of("say", "sound", "particles", "var", "state", "tool"), ActSpec.DYING_OK);
        assertTrue(ActSpec.runsWhileDying("say"));
        for (String k : new String[] {"walk_to", "teleport", "set", "remove", "attack"})
            assertFalse(ActSpec.runsWhileDying(k), k);
    }

    @Test void sayTextSubstitutes() {
        assertEquals("Welcome, Sam!", ActSpec.sayText("Welcome, $player!", Map.of("player", "Sam"), null));
        assertEquals("Sam hit Bo", ActSpec.sayText("$attacker hit $other", Map.of("attacker", "Sam", "other", "Bo"), null));
        assertEquals("you said hi", ActSpec.sayText("you said $said", Map.of(), "hi"));
    }

    @Test void sayTextLeavesUnmatchedTokens() {
        assertEquals("Hi $players", ActSpec.sayText("Hi $players", Map.of("player", "Sam"), null));
        assertEquals("Hi $owner $player $said", ActSpec.sayText("Hi $owner $player $said", Map.of(), null));
    }

    @Test void sayTextCapsAtHundred() {
        assertTrue(ActSpec.sayText("x".repeat(300), Map.of(), null).length() <= 100);
        assertTrue(ActSpec.sayText("$player", Map.of("player", "y".repeat(300)), null).length() <= 100);
    }

    @Test void sayRoles() {
        assertTrue(ActSpec.actions(j("[{'say':'Hi $attacker'}]"), 8).get(0).roles().contains("attacker"));
        assertTrue(ActSpec.actions(j("[{'say':'Hi $players'}]"), 8).get(0).roles().isEmpty());
    }
}
