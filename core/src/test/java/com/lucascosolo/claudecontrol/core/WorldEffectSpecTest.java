package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorldEffectSpecTest {
    private JsonObject request(String world, String action, String value) {
        JsonObject input = new JsonObject();
        input.addProperty("world", world);
        input.addProperty("action", action);
        input.addProperty("value", value);
        return input;
    }

    @Test void namedTimeRequestsMapToDocumentedMinecraftTimes() {
        Map<String, Long> times = Map.of("sunrise", 0L, "day", 1000L, "sunset", 12000L, "night", 13000L);
        times.forEach((name, ticks) -> {
            WorldEffectSpec effect = WorldEffectSpec.parse(request("shared_survival", "time", name));
            assertEquals("shared_survival", effect.world());
            assertEquals("time", effect.action());
            assertEquals(name, effect.value());
            assertEquals(ticks.longValue(), effect.timeTicks());
        });
    }

    @Test void weatherSupportsClearRainAndThunderWithBoundedDefaultDuration() {
        for (String value : new String[]{"clear", "rain", "thunder"}) {
            WorldEffectSpec effect = WorldEffectSpec.parse(request("all", "weather", value));
            assertEquals("all", effect.world());
            assertEquals("weather", effect.action());
            assertEquals(value, effect.value());
            assertEquals(300, effect.durationSeconds());
        }
    }

    @Test void weatherAcceptsExplicitDurationBoundarySeconds() {
        for (int duration : new int[]{1, 1800}) {
            JsonObject input = request("world", "weather", "rain");
            input.addProperty("duration_seconds", duration);
            assertEquals(duration, WorldEffectSpec.parse(input).durationSeconds());
        }
    }

    @Test void rejectsOutOfRangeFractionalAndNonfiniteDurations() {
        for (double duration : new double[]{0, -1, 1801, 1.5, 1e20, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            JsonObject input = request("world", "weather", "rain");
            input.addProperty("duration_seconds", duration);
            assertThrows(Exception.class, () -> WorldEffectSpec.parse(input), "duration=" + duration);
        }
    }

    @Test void durationCannotBeCoercedFromStringBooleanNullOrArray() {
        for (var duration : new com.google.gson.JsonElement[]{new JsonPrimitive("300"), new JsonPrimitive(true), JsonNull.INSTANCE, new JsonArray()}) {
            JsonObject input = request("world", "weather", "rain");
            input.add("duration_seconds", duration);
            assertThrows(Exception.class, () -> WorldEffectSpec.parse(input));
        }
    }

    @Test void worldIsRequiredTypedNonblankAndLengthBounded() {
        JsonObject missing = request("world", "time", "day");
        missing.remove("world");
        assertThrows(Exception.class, () -> WorldEffectSpec.parse(missing));
        for (String world : new String[]{"", "   ", "w".repeat(129)}) {
            assertThrows(Exception.class, () -> WorldEffectSpec.parse(request(world, "time", "day")));
        }
        for (var world : new com.google.gson.JsonElement[]{JsonNull.INSTANCE, new JsonPrimitive(42), new JsonPrimitive(false), new JsonArray()}) {
            JsonObject input = request("world", "time", "day");
            input.add("world", world);
            assertThrows(Exception.class, () -> WorldEffectSpec.parse(input));
        }
        assertEquals("w".repeat(128), WorldEffectSpec.parse(request("w".repeat(128), "time", "day")).world());
    }

    @Test void actionAndValueMustBePresentAndBelongToSupportedEffect() {
        for (String field : new String[]{"action", "value"}) {
            JsonObject missing = request("world", "time", "day");
            missing.remove(field);
            assertThrows(Exception.class, () -> WorldEffectSpec.parse(missing));
        }
        for (JsonObject unsupported : new JsonObject[]{
            request("world", "summon", "lightning"), request("world", "time", "rain"),
            request("world", "weather", "night"), request("world", "weather", "storm forever"),
            request("world", "time", "24000"), request("world", "", "day")}) {
            assertThrows(Exception.class, () -> WorldEffectSpec.parse(unsupported));
        }
    }

    @Test void timeDoesNotUseDurationButRejectsInvalidSuppliedDuration() {
        JsonObject valid = request("world", "time", "sunset");
        valid.addProperty("duration_seconds", 600);
        assertEquals(12000L, WorldEffectSpec.parse(valid).timeTicks());
        for (var invalid : new com.google.gson.JsonElement[]{new JsonPrimitive(0), new JsonPrimitive(1801), new JsonPrimitive(1.5), new JsonPrimitive("600")}) {
            JsonObject input = request("world", "time", "sunset");
            input.add("duration_seconds", invalid);
            assertThrows(Exception.class, () -> WorldEffectSpec.parse(input));
        }
    }
}
