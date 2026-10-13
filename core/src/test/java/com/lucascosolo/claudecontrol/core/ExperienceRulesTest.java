package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExperienceRulesTest {
    private static final String ALICE = "00000000-0000-0000-0000-000000000001";
    private static final String BOB = "00000000-0000-0000-0000-000000000002";
    private ExperienceSpec spec(String properties) {
        JsonObject definition = ExperienceSpecTest.definition();
        JsonObject rule = JsonParser.parseString("{\"id\":\"find\",\"event\":\"chat\",\"function\":\"found\"," + properties + "}").getAsJsonObject();
        JsonArray rules = new JsonArray(); rules.add(rule); definition.add("rules", rules);
        return ExperienceSpec.parse(definition);
    }
    private JsonObject event(String player) {
        return JsonParser.parseString("{\"type\":\"chat\",\"player_uuid\":\"" + player + "\",\"player_name\":\"Player\",\"message\":\"found clue\",\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0}").getAsJsonObject();
    }

    @Test void thresholdFiresNamedCallbackWithParamsAndLeavesCallerStateUntouched() {
        ExperienceSpec spec = spec("\"threshold\":2,\"params\":{\"reward\":\"clue\"}");
        JsonObject original = new JsonObject(); original.addProperty("chapter", 3); JsonObject before = original.deepCopy();
        var first = ExperienceRules.evaluate(spec, event(ALICE), original, 10000);
        assertTrue(first.calls().isEmpty()); assertEquals(before, original);
        var second = ExperienceRules.evaluate(spec, event(ALICE), first.state(), 11000);
        assertEquals(1, second.calls().size()); assertEquals("found", second.calls().get(0).function());
        assertEquals("clue", second.calls().get(0).params().get("reward").getAsString());
        assertEquals(3, second.state().get("chapter").getAsInt());
        assertTrue(ExperienceRules.evaluate(spec, event(ALICE), second.state(), 20000).calls().isEmpty());
    }

    @Test void onceIsPerPlayerUnlessSharedWasExplicitlyRequested() {
        ExperienceSpec personal = spec("\"threshold\":1");
        var first = ExperienceRules.evaluate(personal, event(ALICE), new JsonObject(), 10000);
        assertEquals(1, first.calls().size());
        assertTrue(ExperienceRules.evaluate(personal, event(ALICE), first.state(), 20000).calls().isEmpty());
        assertEquals(1, ExperienceRules.evaluate(personal, event(BOB), first.state(), 20000).calls().size());
        ExperienceSpec shared = spec("\"threshold\":2,\"shared\":true");
        var a = ExperienceRules.evaluate(shared, event(ALICE), new JsonObject(), 10000);
        assertTrue(a.calls().isEmpty());
        var b = ExperienceRules.evaluate(shared, event(BOB), a.state(), 11000);
        assertEquals(1, b.calls().size());
        assertTrue(ExperienceRules.evaluate(shared, event(ALICE), b.state(), 20000).calls().isEmpty());
    }

    @Test void repeatingRuleCooldownUsesInjectedClockAndExactBoundary() {
        ExperienceSpec spec = spec("\"once\":false,\"cooldown_seconds\":2");
        var first = ExperienceRules.evaluate(spec, event(ALICE), new JsonObject(), 10000);
        assertEquals(1, first.calls().size());
        var early = ExperienceRules.evaluate(spec, event(ALICE), first.state(), 11999);
        assertTrue(early.calls().isEmpty());
        var boundary = ExperienceRules.evaluate(spec, event(ALICE), early.state(), 12000);
        assertEquals(1, boundary.calls().size());
        assertTrue(ExperienceRules.evaluate(spec, event(ALICE), boundary.state(), 11000).calls().isEmpty());
    }

    @Test void filtersRejectUnmatchedEventsWithoutAdvancingProgress() {
        ExperienceSpec spec = spec("\"threshold\":2,\"contains\":\"clue\",\"world\":\"world\",\"area\":{\"x\":0,\"y\":64,\"z\":0,\"radius\":2}");
        JsonObject state = new JsonObject();
        for (String field : new String[]{"type", "message", "world", "x"}) {
            JsonObject input = event(ALICE);
            if (field.equals("x")) input.addProperty(field, 3); else input.addProperty(field, field.equals("type") ? "join" : "unmatched");
            var ignored = ExperienceRules.evaluate(spec, input, state, 10000);
            assertTrue(ignored.calls().isEmpty()); state = ignored.state();
        }
        var first = ExperienceRules.evaluate(spec, event(ALICE), state, 11000);
        assertTrue(first.calls().isEmpty());
        assertEquals(1, ExperienceRules.evaluate(spec, event(ALICE), first.state(), 12000).calls().size());
    }

    @Test void missingIdentityFailsClosedAndStateSizeIsBounded() {
        ExperienceSpec spec = spec("\"threshold\":1");
        JsonObject input = event(ALICE); input.remove("player_uuid");
        assertThrows(IllegalArgumentException.class, () -> ExperienceRules.evaluate(spec, input, new JsonObject(), 10000));
        JsonObject oversized = new JsonObject(); oversized.addProperty("payload", "x".repeat(32769));
        assertThrows(IllegalArgumentException.class, () -> ExperienceRules.evaluate(spec, event(ALICE), oversized, 10000));
    }

    @Test void invalidReplacementDoesNotMutateExistingProgress() {
        ExperienceSpec working = spec("\"threshold\":2");
        var progress = ExperienceRules.evaluate(working, event(ALICE), new JsonObject(), 10000);
        JsonObject before = progress.state().deepCopy(); JsonObject replacement = ExperienceSpecTest.definition();
        replacement.addProperty("id", "bad/id");
        assertThrows(IllegalArgumentException.class, () -> ExperienceSpec.parse(replacement));
        assertEquals(before, progress.state());
        assertEquals(1, ExperienceRules.evaluate(working, event(ALICE), progress.state(), 11000).calls().size());
    }
}
