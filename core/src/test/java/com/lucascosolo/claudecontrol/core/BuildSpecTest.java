package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildSpecTest {
    private JsonObject valid() {
        return JsonParser.parseString("""
            {"world":"world","x":10,"y":80,"z":10,"width":2,"depth":2,"height":2,
             "replace_existing":false,"operations":[{"x":0,"y":0,"z":0,"material":"STONE"}]}
            """).getAsJsonObject();
    }

    @Test void acceptsBoundedRelativeBlockPlan() {
        BuildSpec spec = BuildSpec.parse(valid());
        assertEquals(1, spec.blocks().size());
        assertEquals(0, spec.blocks().get(0).x());
        assertEquals("STONE", spec.blocks().get(0).material());
    }

    @Test void rejectsMissingEmptyFractionalAndOutOfRangeOperations() {
        JsonObject missing = valid(); missing.remove("operations");
        assertThrows(Exception.class, () -> BuildSpec.parse(missing));
        JsonObject empty = valid(); empty.getAsJsonArray("operations").remove(0);
        assertThrows(Exception.class, () -> BuildSpec.parse(empty));
        for (double coordinate : new double[]{-1, 2, 0.5}) {
            JsonObject invalid = valid(); invalid.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("x", coordinate);
            assertThrows(Exception.class, () -> BuildSpec.parse(invalid));
        }
    }

    @Test void rejectsOversizedVolumesAndDuplicateCoordinates() {
        JsonObject oversized = valid(); oversized.addProperty("width", 32); oversized.addProperty("depth", 32); oversized.addProperty("height", 32);
        assertThrows(Exception.class, () -> BuildSpec.parse(oversized));
        JsonObject duplicate = valid(); duplicate.getAsJsonArray("operations").add(duplicate.getAsJsonArray("operations").get(0).deepCopy());
        assertThrows(Exception.class, () -> BuildSpec.parse(duplicate));
        JsonObject distant = valid(); distant.addProperty("x", 30000001);
        assertThrows(Exception.class, () -> BuildSpec.parse(distant));
    }

    @Test void rejectsHazardousMaterialBeforeAnyEffects() {
        for (String material : new String[]{"TNT", "COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "STRUCTURE_BLOCK", "JIGSAW", "BARRIER", "BEDROCK", "LAVA", "FIRE"}) {
            JsonObject invalid = valid(); invalid.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("material", material);
            assertThrows(Exception.class, () -> BuildSpec.parse(invalid), material);
        }
    }

    @Test void rejectsDamagingSurfacesGravityBlocksAndMechanisms() {
        for (String material : new String[]{"CAMPFIRE", "SOUL_CAMPFIRE", "MAGMA_BLOCK", "CACTUS", "POWDER_SNOW",
            "SWEET_BERRY_BUSH", "WITHER_ROSE", "SAND", "RED_SAND", "GRAVEL", "ANVIL", "CHIPPED_ANVIL",
            "DAMAGED_ANVIL", "WHITE_CONCRETE_POWDER", "PISTON", "STICKY_PISTON", "DISPENSER", "DROPPER",
            "OBSERVER", "HOPPER"}) {
            JsonObject invalid = valid();
            invalid.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("material", material);
            assertThrows(Exception.class, () -> BuildSpec.parse(invalid), material);
        }
    }
}
