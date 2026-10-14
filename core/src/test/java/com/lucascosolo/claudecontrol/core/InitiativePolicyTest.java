package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InitiativePolicyTest {
    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private void allowed(String name, String json) {
        assertDoesNotThrow(() -> InitiativePolicy.require(name, args(json)), name + ": " + json);
    }
    private void refused(String name, String json) {
        assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.require(name, args(json)), name + ": " + json);
    }
    private JsonObject build(int count) {
        JsonObject plan = args("{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"width\":16,\"height\":1,\"depth\":1,\"operations\":[]}");
        plan.addProperty("width", Math.max(16, count));
        for (int i = 0; i < count; i++) {
            plan.getAsJsonArray("operations").add(args("{\"x\":" + i + ",\"y\":0,\"z\":0,\"material\":\"OAK_PLANKS\"}"));
        }
        return plan;
    }

    @Test void permitsObservationSurveyAndTypedChat() {
        allowed("observe_world", "{}");
        allowed("survey_area", "{}");
        allowed("survey_area", "{\"radius\":16}");
        allowed("send_chat", "{\"message\":\"I have a small idea for our town.\"}");
        refused("send_chat", "{}");
        refused("send_chat", "{\"message\":null}");
        refused("send_chat", "{\"message\":123}");
    }
    @Test void refusesConsequentialAndUnknownToolsEvenWithWellFormedArguments() {
        refused("run_commands", "{\"commands\":[\"say hello\"]}");
        refused("teleport_player", "{\"x\":0,\"y\":64,\"z\":0}");
        refused("world_effect", "{\"world\":\"world\",\"action\":\"time\",\"value\":\"day\"}");
        refused("execute_shell", "{}");
        refused("", "{}");
        assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.require(null, new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.require("observe_world", null));
    }
    @Test void limitsCompanionToInvitedBehaviorAndStrictActionTypes() {
        for (String action : List.of("follow", "wave", "status", "wander", "design")) {
            allowed("companion", "{\"action\":\"" + action + "\"}");
        }
        allowed("companion", "{\"action\":\"move\",\"x\":0,\"y\":64,\"z\":0}");
        allowed("companion", "{\"action\":\"wander\",\"duration_seconds\":10}");
        allowed("companion", "{\"action\":\"design\",\"duration_seconds\":60}");
        for (String action : List.of("summon", "stay", "dismiss", "unknown")) {
            refused("companion", "{\"action\":\"" + action + "\"}");
        }
        for (String json : List.of("{}", "{\"action\":null}", "{\"action\":true}", "{\"action\":[]}",
            "{\"action\":\"move\"}", "{\"action\":\"move\",\"x\":\"0\",\"y\":64,\"z\":0}",
            "{\"action\":\"wander\",\"duration_seconds\":9}", "{\"action\":\"design\",\"duration_seconds\":61}")) {
            refused("companion", json);
        }
    }
    @Test void selfCosmeticAppearanceDoesNotGrantOtherEntityOrHostPowers() {
        allowed("companion", "{\"action\":\"appearance\",\"preset\":\"hatter\"}");
        allowed("companion", "{\"action\":\"appearance\",\"account\":\"TownOwner\",\"preset\":\"explorer\"}");
        allowed("companion", "{\"action\":\"appearance\",\"account\":\"\"}");
        refused("companion", "{\"action\":\"appearance\",\"account\":\"https://example.invalid/skin\"}");
        for(String extra:List.of("url","texture","entity_id","player","command")) {
            JsonObject input=args("{\"action\":\"appearance\",\"preset\":\"hatter\"}");input.addProperty(extra,"untrusted");
            assertThrows(IllegalArgumentException.class,()->InitiativePolicy.require("companion",input),extra);
        }
        refused("entity_commands", "{\"commands\":[\"summon cow ~ ~ ~\"]}");
        refused("run_commands", "{\"commands\":[\"summon cow ~ ~ ~\"]}");
        for(String action:List.of("summon","dismiss","stay"))refused("companion","{\"action\":\""+action+"\"}");
    }
    @Test void allowsDurableNotesAndRefusesArchivalOrMalformedFields() {
        allowed("memory", "{\"action\":\"list\"}");
        allowed("memory", "{\"action\":\"read\",\"name\":\"town.md\"}");
        allowed("memory", "{\"action\":\"write\",\"name\":\"town.md\",\"text\":\"Town ideas\"}");
        refused("memory", "{\"action\":\"archive\",\"name\":\"town.md\"}");
        for (String json : List.of("{}", "{\"action\":false}", "{\"action\":\"read\"}",
            "{\"action\":\"read\",\"name\":42}", "{\"action\":\"write\",\"name\":\"town.md\"}",
            "{\"action\":\"write\",\"name\":\"town.md\",\"text\":null}")) refused("memory", json);
    }
    @Test void giftsRequireSmallIntegerAmountsAndCannotSelectAnotherPlayer() {
        allowed("give_item", "{\"material\":\"BREAD\",\"amount\":1}");
        allowed("give_item", "{\"material\":\"OAK_PLANKS\",\"amount\":16}");
        for (String amount : List.of("0", "17", "-1", "1.5", "1e999", "\"1\"", "true", "null", "[]", "{}")) {
            refused("give_item", "{\"material\":\"BREAD\",\"amount\":" + amount + "}");
        }
        refused("give_item", "{\"material\":\"BREAD\"}");
        refused("give_item", "{\"amount\":1}");
        refused("give_item", "{\"material\":7,\"amount\":1}");
        for (String player : List.of("\"friend\"", "\"\"", "null")) {
            refused("give_item", "{\"material\":\"BREAD\",\"amount\":1,\"player\":" + player + "}");
        }
    }
    @Test void buildPlansAreSmallTypedAndNonReplacing() {
        for (String tool : List.of("build", "build_in_character")) {
            assertDoesNotThrow(() -> InitiativePolicy.require(tool, build(1)));
            assertDoesNotThrow(() -> InitiativePolicy.require(tool, build(16)));
            assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.require(tool, build(0)));
            assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.require(tool, build(17)));
            JsonObject replacing = build(1);
            replacing.addProperty("replace_existing", true);
            assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.require(tool, replacing));
            for (String malformed : List.of("{}", "{\"operations\":null}", "{\"operations\":\"one\"}", "{\"operations\":[null]}")) {
                refused(tool, malformed);
            }
        }
    }
    @Test void managedExperienceActionsNeedTypedIdsAndSaveDefinition() {
        allowed("experience", "{\"action\":\"list\"}");
        allowed("experience", "{\"action\":\"read\",\"id\":\"town-story\"}");
        allowed("experience", "{\"action\":\"reload\",\"id\":\"town-story\"}");
        JsonObject save = args("{\"action\":\"save\",\"id\":\"town-story\"}");
        save.add("definition", args("{\"id\":\"town-story\",\"title\":\"Town story\",\"script\":\"function onEvent(event,state,game) {}\",\"script_events\":[\"chat\"]}"));
        assertDoesNotThrow(() -> InitiativePolicy.require("experience", save));
        for (String action : List.of("enable", "disable", "delete", "unknown")) {
            refused("experience", "{\"action\":\"" + action + "\",\"id\":\"town-story\"}");
        }
        for (String action : List.of("read", "save", "reload")) {
            refused("experience", "{\"action\":\"" + action + "\"}");
            refused("experience", "{\"action\":\"" + action + "\",\"id\":null}");
            refused("experience", "{\"action\":\"" + action + "\",\"id\":42}");
        }
        refused("experience", "{\"action\":\"save\",\"id\":\"town-story\"}");
        refused("experience", "{\"action\":\"save\",\"id\":\"town-story\",\"definition\":[]}");
    }
    @Test void rejectsDeepOversizedOrUntypedArgumentsWithNormalizedExceptions() {
        JsonObject deep = args("{\"action\":\"save\",\"id\":\"town-story\"}");
        JsonObject nested = new JsonObject();
        deep.add("definition", nested);
        for (int level = 0; level < 34; level++) {
            JsonObject child = new JsonObject();
            nested.add("nested", child);
            nested = child;
        }
        assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.require("experience", deep));
        JsonObject oversized = args("{\"action\":\"write\",\"name\":\"town.md\"}");
        oversized.addProperty("text", "é".repeat(16384));
        assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.require("memory", oversized));
        for (String radius : List.of("0", "17", "1.5", "\"1\"", "true", "null")) {
            refused("survey_area", "{\"radius\":" + radius + "}");
        }
        for (String duration : List.of("1.5", "\"10\"", "true", "null")) {
            refused("companion", "{\"action\":\"wander\",\"duration_seconds\":" + duration + "}");
        }
        refused("send_chat", "{\"message\":\"hello\",\"private\":\"true\"}");
        refused("send_chat", "{\"message\":\"hello\",\"broadcast\":true}");
        refused("observe_world", "{\"player\":\"someone else\"}");
    }
    @Test void defaultPlanningDelaySpansThirtyToFortyFiveMinutes() {
        for (int offset = 0; offset <= 900; offset++) {
            int delay = InitiativePolicy.nextDelaySeconds(1800, offset);
            assertEquals(1800 + offset, delay);
            assertTrue(delay >= 1800 && delay <= 2700);
        }
        assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.nextDelaySeconds(1800, -1));
        assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.nextDelaySeconds(1800, 901));
    }
    @Test void customPlanningDelayCapsAtOneHourAndRefusesInvalidBounds() {
        for (int base : List.of(60, 61, 300, 1800, 2400, 3000, 3599, 3600)) {
            int maximumOffset = Math.min(base / 2, 3600 - base);
            assertEquals(base, InitiativePolicy.nextDelaySeconds(base, 0));
            assertEquals(base + maximumOffset, InitiativePolicy.nextDelaySeconds(base, maximumOffset));
            assertTrue(InitiativePolicy.nextDelaySeconds(base, maximumOffset) <= 3600);
            assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.nextDelaySeconds(base, -1));
            assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.nextDelaySeconds(base, maximumOffset + 1));
        }
        for (int base : List.of(Integer.MIN_VALUE, -1, 0, 59, 3601, Integer.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.nextDelaySeconds(base, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.nextDelaySeconds(1800, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> InitiativePolicy.nextDelaySeconds(1800, Integer.MIN_VALUE));
    }
    @Test void initiativeIntervalDefaultsAndBoundsAreStrictFiniteIntegers() {
        assertEquals(1800, Settings.from(Map.of(), Map.of()).initiativeIntervalSeconds());
        for (int interval : List.of(60, 1800, 3600)) {
            assertEquals(interval, Settings.from(Map.of("initiative-interval-seconds", interval), Map.of()).initiativeIntervalSeconds());
        }
        for (Object invalid : List.of(59, 3601, -1, 60.5, "300", true, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> Settings.from(Map.of("initiative-interval-seconds", invalid), Map.of()));
        }
        Map<String,Object> nullValue = new HashMap<>();
        nullValue.put("initiative-interval-seconds", null);
        assertThrows(IllegalArgumentException.class, () -> Settings.from(nullValue, Map.of()));
    }
}
