package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// I7: hold order is decided per key, not per whole hold.
class TraitSpecHoldKeySeqTest {
    private static Map<String, String> m(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    @Test void seqOfFallsBackToWholeHoldSeq() {
        TraitSpec.Hold h = new TraitSpec.Hold("A", 5, 100, m("k", "0"), m("k", "1"), Map.of("name", 9L));
        assertEquals(9, h.seqOf("name"));
        assertEquals(5, h.seqOf("silent"));
    }

    @Test void fiveArgConstructorHasNoKeySeqs() {
        TraitSpec.Hold h = new TraitSpec.Hold("A", 5, 100, m("k", "0"), m("k", "1"));
        assertEquals(Map.of(), h.keySeqs());
        assertEquals(5, h.seqOf("k"));
    }

    @Test void emptyKeySeqsBehaveAsWholeHoldSeq() {
        TraitSpec.Hold a = new TraitSpec.Hold("A", 1, 100, m("size", "X"), m("size", "a"));
        TraitSpec.Hold b = new TraitSpec.Hold("B", 2, 100, m("size", "a"), m("size", "b"));
        TraitSpec.Ending e = TraitSpec.end(a, List.of(b), m("size", "b"));
        assertEquals(Map.of(), e.put());
        assertEquals(Map.of("B", Map.of("size", "X")), e.handoff());
    }

    @Test void olderKeySeqIsNotStackedOnNewerEndingKey() {
        // O's whole seq is newer, but its size key was written before the ending hold's.
        TraitSpec.Hold ending = new TraitSpec.Hold("E", 2, 100, m("size", "X"), m("size", "a"), Map.of("size", 2L));
        TraitSpec.Hold o = new TraitSpec.Hold("O", 5, 100, m("size", "a"), m("size", "b"), Map.of("size", 1L));
        TraitSpec.Ending e = TraitSpec.end(ending, List.of(o), m("size", "a"));
        assertEquals(Map.of(), e.handoff());
        assertEquals(Map.of("size", "X"), e.put());
    }

    @Test void behaviourHoldKeepsOriginalAcrossTimedSetOnOtherKey() {
        TraitSpec.Hold b = new TraitSpec.Hold("B", 1, 900, m("silent", "false"), m("silent", "true"), Map.of("silent", 1L));
        TraitSpec.Hold t = new TraitSpec.Hold("T", 2, 100, m("name", ""), m("name", "A"), Map.of("name", 2L));
        // B then sets name: it takes over T's value and stacks on it.
        b = new TraitSpec.Hold("B", 1, 900, m("silent", "false", "name", "A"), m("silent", "true", "name", "B"), Map.of("silent", 1L, "name", 3L));

        TraitSpec.Ending endT = TraitSpec.end(t, List.of(b), m("name", "B"));
        assertEquals(Map.of(), endT.put());
        assertEquals(Map.of("B", Map.of("name", "")), endT.handoff());

        TraitSpec.Hold b2 = new TraitSpec.Hold("B", 1, 900, m("silent", "false", "name", ""), m("silent", "true", "name", "B"),
                Map.of("silent", 1L, "name", 3L));
        TraitSpec.Ending endB = TraitSpec.end(b2, List.of(), m("silent", "true", "name", "B"));
        assertEquals("", endB.put().get("name"));
        assertEquals("false", endB.put().get("silent"));
        assertTrue(endB.handoff().isEmpty());
    }

    @Test void lowestKeySeqWinsAmongStacked() {
        TraitSpec.Hold ending = new TraitSpec.Hold("E", 1, 100, m("k", "0"), m("k", "1"), Map.of("k", 1L));
        TraitSpec.Hold far = new TraitSpec.Hold("F", 1, 100, m("k", "1"), m("k", "2"), Map.of("k", 7L));
        TraitSpec.Hold near = new TraitSpec.Hold("N", 1, 100, m("k", "1"), m("k", "3"), Map.of("k", 4L));
        TraitSpec.Ending e = TraitSpec.end(ending, List.of(far, near), m("k", "3"));
        assertEquals(Map.of("N", Map.of("k", "0")), e.handoff());
    }
}
