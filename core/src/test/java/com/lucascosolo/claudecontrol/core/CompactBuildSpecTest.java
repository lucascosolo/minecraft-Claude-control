package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompactBuildSpecTest {
    private JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private JsonObject plan(int width, int height, int depth) {
        JsonObject plan = object("{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"operations\":[]}");
        plan.addProperty("width", width); plan.addProperty("height", height); plan.addProperty("depth", depth);
        return plan;
    }
    private JsonObject box(int x, int y, int z, int x2, int y2, int z2, String mode, String material) {
        JsonObject box = object("{\"shape\":\"box\"}");
        box.addProperty("x", x); box.addProperty("y", y); box.addProperty("z", z);
        box.addProperty("x2", x2); box.addProperty("y2", y2); box.addProperty("z2", z2);
        box.addProperty("mode", mode);
        if (material != null) box.addProperty("material", material);
        return box;
    }
    private void add(JsonObject plan, JsonObject operation) { plan.getAsJsonArray("operations").add(operation); }
    private Map<String,String> blocks(JsonObject plan) {
        Map<String,String> result = new HashMap<>();
        for (var block : BuildSpec.parse(plan).blocks()) {
            assertNull(result.put(block.x()+","+block.y()+","+block.z(), block.material()));
        }
        return result;
    }
    @Test void hollowRoomHasCoherentWallsDoorOpeningAndWindowPatch() {
        JsonObject plan = plan(5,4,5);
        add(plan, box(0,0,0,4,3,4,"shell","OAK_PLANKS"));
        assertEquals(82, blocks(plan).size());
        add(plan, box(2,1,0,2,2,0,"omit",null));
        add(plan, box(0,1,2,0,1,2,"solid","GLASS"));
        Map<String,String> finalBlocks = blocks(plan);
        assertEquals(80, finalBlocks.size());
        assertFalse(finalBlocks.containsKey("2,1,0"));
        assertFalse(finalBlocks.containsKey("2,2,0"));
        assertEquals("GLASS", finalBlocks.get("0,1,2"));
        for (int x=1;x<4;x++) for(int y=1;y<3;y++) for(int z=1;z<4;z++) {
            assertFalse(finalBlocks.containsKey(x+","+y+","+z));
        }
        assertTrue(finalBlocks.values().stream().noneMatch("AIR"::equals));
    }
    @Test void inclusiveSolidShapesAndDetailsOverrideGeneratedCells() {
        JsonObject plan = plan(3,2,2);
        add(plan, box(0,0,0,2,1,1,"solid","STONE"));
        assertEquals(12, blocks(plan).size());
        add(plan, box(1,0,0,2,1,1,"solid","GLASS"));
        add(plan, object("{\"x\":2,\"y\":1,\"z\":1,\"material\":\"OAK_PLANKS\"}"));
        Map<String,String> result = blocks(plan);
        assertEquals(12, result.size());
        assertEquals("STONE", result.get("0,1,1"));
        assertEquals("GLASS", result.get("1,1,1"));
        assertEquals("OAK_PLANKS", result.get("2,1,1"));
    }
    @Test void omitRemovesOnlyPlannedCellsAndExplicitDuplicatesStillFail() {
        JsonObject plan = plan(3,1,1);
        add(plan, box(0,0,0,0,0,0,"solid","STONE"));
        add(plan, box(1,0,0,2,0,0,"omit",null));
        assertEquals(Map.of("0,0,0","STONE"), blocks(plan));
        JsonObject point = object("{\"x\":1,\"y\":0,\"z\":0,\"material\":\"GLASS\"}");
        add(plan, point);
        add(plan, box(1,0,0,1,0,0,"omit",null));
        add(plan, point.deepCopy());
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(plan));
    }
    @Test void shapeCoordinatesAndModesFailClosedWithoutNumericCoercion() {
        for (String field : List.of("x","y","z","x2","y2","z2")) {
            for (JsonElement invalid : List.of(new JsonPrimitive("0"),new JsonPrimitive(0.5),JsonNull.INSTANCE,new JsonPrimitive(true))) {
                JsonObject plan = plan(2,2,2);
                JsonObject shape = box(0,0,0,1,1,1,"solid","STONE");
                shape.add(field, invalid); add(plan, shape);
                assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(plan), field);
            }
        }
        for (String missing : List.of("x2","y2","z2","material")) {
            JsonObject plan = plan(2,2,2);
            JsonObject shape = box(0,0,0,1,1,1,"solid","STONE");
            shape.remove(missing); add(plan,shape);
            assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(plan), missing);
        }
        JsonObject defaultSolid = plan(2,2,2);
        JsonObject defaultShape = box(0,0,0,1,1,1,"solid","STONE");
        defaultShape.remove("mode"); add(defaultSolid, defaultShape);
        assertEquals(8, blocks(defaultSolid).size());
        for (String mode : List.of("air","replace","SHELL","")) {
            JsonObject plan = plan(2,2,2); add(plan,box(0,0,0,1,1,1,mode,"STONE"));
            assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(plan));
        }
    }
    @Test void compactMetadataRequiresBoxShapeAndEmptyFinalPlanCannotClearWorldBlocks() {
        for(String extra:List.of("x2","y2","z2","mode")) {
            JsonObject plan=plan(2,2,2);
            JsonObject point=object("{\"x\":0,\"y\":0,\"z\":0,\"material\":\"STONE\"}");
            if(extra.equals("mode"))point.addProperty(extra,"solid");else point.addProperty(extra,1);
            add(plan,point);assertThrows(IllegalArgumentException.class,()->BuildSpec.parse(plan),extra);
        }
        JsonObject empty=plan(2,2,2);add(empty,box(0,0,0,1,1,1,"solid","STONE"));add(empty,box(0,0,0,1,1,1,"omit",null));
        assertThrows(IllegalArgumentException.class,()->BuildSpec.parse(empty));
        JsonObject overridden=plan(1,1,1);add(overridden,object("{\"x\":0,\"y\":0,\"z\":0,\"material\":\"STONE\"}"));
        add(overridden,box(0,0,0,0,0,0,"solid","GLASS"));assertEquals(Map.of("0,0,0","GLASS"),blocks(overridden));
    }
    @Test void boundsAndHazardsCannotBeHiddenByLaterOmissions() {
        for (String material : List.of("TNT","LAVA","COMMAND_BLOCK","SAND")) {
            JsonObject plan = plan(2,2,2);
            add(plan,box(0,0,0,1,1,1,"solid",material));
            add(plan,box(0,0,0,1,1,1,"omit",null));
            assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(plan), material);
        }
        for (JsonObject shape : List.of(box(-1,0,0,1,1,1,"omit",null),box(0,0,0,2,1,1,"omit",null),box(1,0,0,0,1,1,"solid","STONE"))) {
            JsonObject plan=plan(2,2,2); add(plan,box(0,0,0,0,0,0,"solid","STONE")); add(plan,shape);
            assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(plan));
        }
    }
    @Test void enforcesShapeCountExpandedVisitAndBoundingVolumeLimits() {
        JsonObject shapes=plan(1,1,1);
        for(int i=0;i<128;i++)add(shapes,box(0,0,0,0,0,0,"solid","STONE"));
        assertEquals(1, blocks(shapes).size());
        add(shapes,box(0,0,0,0,0,0,"solid","STONE"));
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(shapes));
        JsonObject visits=plan(32,16,16);
        for(int i=0;i<8;i++)add(visits,box(0,0,0,31,15,15,"solid","STONE"));
        assertEquals(8192, blocks(visits).size());
        add(visits,box(0,0,0,31,15,15,"solid","STONE"));
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(visits));
        JsonObject volume=plan(32,32,32);add(volume,box(0,0,0,0,0,0,"solid","STONE"));
        assertThrows(IllegalArgumentException.class, () -> BuildSpec.parse(volume));
    }
}
