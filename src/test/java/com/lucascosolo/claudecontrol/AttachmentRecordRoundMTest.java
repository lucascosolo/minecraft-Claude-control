package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class AttachmentRecordRoundMTest {
    private static AttachmentRecord rec(String paused, int cover, List<Integer> fires) {
        return new AttachmentRecord("a1", "guard", 4, "villager", new LinkedHashMap<>(), new Authority(List.of("Alice"), false, false, true), null,
                new LinkedHashMap<>(), 90, UUID.fromString("11111111-1111-1111-1111-111111111111"), "world", 1, 2, 3, List.of(), 5L, paused, cover, fires);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEATH", "DESPAWN", "DROP", "EXPLODE", "HIT", "MERGE", "OUT_OF_WORLD", "PICKUP", "PLUGIN", "TRANSFORMATION", "DISCARD"})
    void endingCauses(String cause) { assertTrue(AttachmentRecord.endsOnRemoval(cause), cause); }

    @ParameterizedTest
    @ValueSource(strings = {"UNLOAD", "PLAYER_QUIT", "ENTER_BLOCK", "SOMETHING_NEW", ""})
    @NullSource
    void keepingCauses(String cause) { assertFalse(AttachmentRecord.endsOnRemoval(cause), String.valueOf(cause)); }

    @Test void roundTripKeepsPausedCoverAndFires() {
        var r = rec("sleeping", 7, List.of(3, 9, 12));
        var back = AttachmentRecord.fromJson(r.toJson());
        assertEquals(r, back);
        assertEquals("sleeping", back.paused());
        assertEquals(7, back.coverUsed());
        assertEquals(List.of(3, 9, 12), back.fires());
    }

    @Test void pausedWrittenOnlyWhenSet() {
        assertFalse(rec(null, 0, List.of()).toJson().has("paused"));
        var o = rec("x", 4, List.of(1)).toJson();
        assertEquals("x", o.get("paused").asText());
        assertEquals(4, o.get("cover_used").asInt());
        assertTrue(o.get("fires").isArray());
        assertEquals(1, o.get("fires").get(0).asInt());
    }

    @Test void oldRecordWithoutNewFieldsStillParses() {
        var o = (ObjectNode) rec("x", 4, List.of(1)).toJson().deepCopy();
        o.remove("paused"); o.remove("cover_used"); o.remove("fires");
        var back = AttachmentRecord.fromJson(o);
        assertNull(back.paused());
        assertEquals(0, back.coverUsed());
        assertEquals(List.of(), back.fires());
    }
}
