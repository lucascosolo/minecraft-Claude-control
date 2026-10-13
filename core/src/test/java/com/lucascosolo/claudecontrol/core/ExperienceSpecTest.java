package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExperienceSpecTest {
    static JsonObject definition() {
        return JsonParser.parseString("""
            {"id":"discovery","title":"Cooperative discovery",
             "script":"function found(event,state,game,params) {}",
             "rules":[{"id":"find","event":"interact","function":"found"}]}
            """).getAsJsonObject();
    }
    private JsonObject rule(JsonObject input) { return input.getAsJsonArray("rules").get(0).getAsJsonObject(); }
    private void reject(JsonObject input) { assertThrows(IllegalArgumentException.class, () -> ExperienceSpec.parse(input)); }

    @Test void acceptsMinimalRuleAndScriptEventDefinitionsWithoutMutatingInput() {
        JsonObject input = definition(); JsonObject before = input.deepCopy();
        assertNotNull(ExperienceSpec.parse(input)); assertEquals(before, input);
        input.remove("rules"); input.addProperty("script", "function onEvent(event,state,game) {}");
        input.add("script_events", JsonParser.parseString("[\"join\",\"chat\"]"));
        assertNotNull(ExperienceSpec.parse(input));
    }

    @Test void rejectsIdentifiersUnknownFieldsAndMissingRequiredFields() {
        for (String id : new String[]{"", "Upper", "a/b", "x".repeat(33)}) {
            JsonObject input = definition(); input.addProperty("id", id); reject(input);
        }
        for (String field : new String[]{"id", "title", "script"}) {
            JsonObject input = definition(); input.remove(field); reject(input);
        }
        JsonObject unknown = definition(); unknown.addProperty("command", "say hello"); reject(unknown);
        JsonObject empty = definition(); empty.remove("rules"); reject(empty);
    }

    @Test void validatesStringLengthsAndDoesNotCoerceTypes() {
        for (String field : new String[]{"id", "title", "description", "script"}) {
            for (JsonElement value : new JsonElement[]{JsonNull.INSTANCE, new JsonPrimitive(1), new JsonPrimitive(true), new JsonArray()}) {
                JsonObject input = definition(); input.add(field, value); reject(input);
            }
        }
        for (String field : new String[]{"title", "description", "script"}) {
            int limit = field.equals("title") ? 80 : field.equals("description") ? 512 : 16384;
            JsonObject input = definition(); input.addProperty(field, "x".repeat(limit + 1)); reject(input);
        }
        JsonObject blank = definition(); blank.addProperty("title", ""); reject(blank);
    }

    @Test void rulesAndEventsMustBeUniqueSupportedAndBounded() {
        JsonObject duplicate = definition(); duplicate.getAsJsonArray("rules").add(rule(duplicate).deepCopy()); reject(duplicate);
        JsonObject tooMany = definition();
        for (int i = 1; i < 17; i++) { JsonObject r = rule(tooMany).deepCopy(); r.addProperty("id", "rule" + i); tooMany.getAsJsonArray("rules").add(r); }
        reject(tooMany);
        for (String event : new String[]{"console", "player_move", "CHAT"}) {
            JsonObject input = definition(); rule(input).addProperty("event", event); reject(input);
        }
        JsonObject duplicateEvents = definition(); duplicateEvents.add("script_events", JsonParser.parseString("[\"chat\",\"chat\"]")); reject(duplicateEvents);
        JsonObject invalidFunction = definition(); rule(invalidFunction).addProperty("function", "game.chat('oops')"); reject(invalidFunction);
        JsonObject unknown = definition(); rule(unknown).addProperty("console", "say hi"); reject(unknown);
    }

    @Test void validatesIntegerBooleanAreaAndParameterBounds() {
        for (String field : new String[]{"threshold", "cooldown_seconds"}) {
            int maximum = field.equals("threshold") ? 1000 : 3600;
            for (JsonElement value : new JsonElement[]{new JsonPrimitive(0), new JsonPrimitive(maximum + 1), new JsonPrimitive(1.5), new JsonPrimitive("2"), JsonNull.INSTANCE}) {
                JsonObject input = definition(); rule(input).add(field, value); reject(input);
            }
        }
        for (String field : new String[]{"once", "shared"}) {
            JsonObject input = definition(); rule(input).addProperty(field, "true"); reject(input);
        }
        for (double radius : new double[]{0, 65, Double.NaN}) {
            JsonObject input = definition(); JsonObject area = JsonParser.parseString("{\"x\":0,\"y\":64,\"z\":0,\"radius\":1}").getAsJsonObject();
            area.addProperty("radius", radius); rule(input).add("area", area); reject(input);
        }
        JsonObject oversized = definition(); JsonObject params = new JsonObject(); params.addProperty("text", "x".repeat(4097)); rule(oversized).add("params", params); reject(oversized);
    }

    @Test void timerRequiresBoundedIntervalAndSingleEventThreshold() {
        JsonObject timer = definition(); rule(timer).addProperty("event", "timer"); reject(timer);
        for (JsonElement value : new JsonElement[]{new JsonPrimitive(4), new JsonPrimitive(3601), new JsonPrimitive(5.5), new JsonPrimitive("5")}) {
            JsonObject input = timer.deepCopy(); rule(input).add("interval_seconds", value); reject(input);
        }
        rule(timer).addProperty("interval_seconds", 5); assertNotNull(ExperienceSpec.parse(timer));
        rule(timer).addProperty("threshold", 2); reject(timer);
    }
}
