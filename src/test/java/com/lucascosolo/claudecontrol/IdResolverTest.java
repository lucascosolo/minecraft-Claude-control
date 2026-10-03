package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

// Assumed: static String IdResolver.resolve(String kind, String input, Set<String> knownIds)
//   known ids are bare lower-case ids (no namespace). Returns the normalised id (trim, lower-case, "minecraft:" stripped) when known;
//   otherwise throws IllegalArgumentException whose message names the kind and the input and lists close matches (at most 5),
//   never ids that are not close.
class IdResolverTest {
    private static final Set<String> KNOWN = Set.of("zombie", "zombie_villager", "zombified_piglin", "cow", "cat", "creeper", "sheep");

    @Test void knownIdsAreNormalised() {
        assertEquals("zombie", IdResolver.resolve("entity", "zombie", KNOWN));
        assertEquals("zombie", IdResolver.resolve("entity", "  Minecraft:ZOMBIE ", KNOWN));
    }

    @Test void unknownIdListsCloseMatchesOnly() {
        var e = assertThrows(IllegalArgumentException.class, () -> IdResolver.resolve("entity", "zombie_villger", KNOWN));
        String m = e.getMessage();
        assertTrue(m.contains("entity") && m.contains("zombie_villger"), m);
        assertTrue(m.contains("zombie_villager"), m);
        assertFalse(m.contains("sheep") || m.contains("cow"), m);
    }

    @Test void prefixTypoFindsCandidates() {
        var e = assertThrows(IllegalArgumentException.class, () -> IdResolver.resolve("entity", "zomb", KNOWN));
        assertTrue(e.getMessage().contains("zombie"), e.getMessage());
    }

    @Test void nothingCloseStillErrors() {
        rejects(() -> IdResolver.resolve("sound", "qqqqqq", KNOWN), "qqqqqq");
        rejects(() -> IdResolver.resolve("sound", "", KNOWN));
        rejects(() -> IdResolver.resolve("sound", null, KNOWN));
    }
}
