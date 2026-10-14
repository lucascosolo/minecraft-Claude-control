package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildBlockDataTest {
    private JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private JsonObject plan() { return object("{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"width\":3,\"height\":3,\"depth\":3,\"operations\":[]}"); }
    private JsonObject point(String material,String state) {
        JsonObject operation=object("{\"x\":0,\"y\":0,\"z\":0}");operation.addProperty("material",material);
        if(state!=null)operation.addProperty("block_data",state);return operation;
    }
    private BuildSpec parse(JsonObject operation) { JsonObject plan=plan();plan.getAsJsonArray("operations").add(operation);return BuildSpec.parse(plan); }
    @Test void absentStateAndLegacyPlacementConstructorKeepEmptyBlockData() {
        var placement=parse(point("STONE",null)).blocks().getFirst();assertEquals("STONE",placement.material());assertEquals("",placement.blockData());
        var legacy=new BuildSpec.BlockPlacement(1,2,3,"STONE");assertEquals("STONE",legacy.material());assertEquals("",legacy.blockData());
    }
    @Test void slabStairAndBareCanonicalStatesSurviveParsingExactly() {
        for(String[] example:List.of(new String[]{"SPRUCE_SLAB","minecraft:spruce_slab[type=top,waterlogged=false]"},
            new String[]{"SPRUCE_STAIRS","minecraft:spruce_stairs[facing=north,half=bottom,shape=straight]"},
            new String[]{"STONE","minecraft:stone"})) {
            JsonObject operation=point(example[0],example[1]),before=operation.deepCopy();var placement=parse(operation).blocks().getFirst();
            assertEquals(example[0],placement.material());assertEquals(example[1],placement.blockData());assertEquals(before,operation);
        }
    }
    @Test void stateOverridesTravelWithExpandedShapeCellsAndLaterDetailsReplaceEarlierState() {
        JsonObject plan=plan(),shape=point("SPRUCE_SLAB","minecraft:spruce_slab[type=bottom]");
        shape.addProperty("shape","box");shape.addProperty("x2",2);plan.getAsJsonArray("operations").add(shape);
        JsonObject detail=point("SPRUCE_SLAB","minecraft:spruce_slab[type=top]");detail.addProperty("x",1);plan.getAsJsonArray("operations").add(detail);
        Map<Integer,String> states=new HashMap<>();for(var block:BuildSpec.parse(plan).blocks())states.put(block.x(),block.blockData());
        assertEquals(Map.of(0,"minecraft:spruce_slab[type=bottom]",1,"minecraft:spruce_slab[type=top]",2,"minecraft:spruce_slab[type=bottom]"),states);
        JsonObject override=point("SPRUCE_SLAB",null);override.addProperty("shape","box");override.addProperty("x",1);plan.getAsJsonArray("operations").add(override);
        var placements=BuildSpec.parse(plan).blocks();assertEquals(3,placements.size());
        assertEquals("",placements.stream().filter(block->block.x()==1).findFirst().orElseThrow().blockData());
    }
    @Test void independentlyOmittedBoxEndpointsDefaultToTheirStartCoordinates() {
        JsonObject single=point("STONE",null);single.addProperty("shape","box");single.addProperty("x",1);single.addProperty("y",1);single.addProperty("z",1);
        var only=parse(single).blocks();assertEquals(1,only.size());assertEquals(new BuildSpec.BlockPlacement(1,1,1,"STONE"),only.getFirst());
        for(String endpoint:List.of("x2","y2","z2")) {
            JsonObject line=single.deepCopy();line.addProperty(endpoint,2);var cells=parse(line).blocks();assertEquals(2,cells.size());
            for(var cell:cells) {
                if(!endpoint.equals("x2"))assertEquals(1,cell.x());if(!endpoint.equals("y2"))assertEquals(1,cell.y());if(!endpoint.equals("z2"))assertEquals(1,cell.z());
            }
        }
    }
    @Test void suppliedEndpointsRemainStrictAndBoundedDespiteOptionalDefaults() {
        for(String endpoint:List.of("x2","y2","z2"))for(JsonElement invalid:List.of(new JsonPrimitive(0.5),new JsonPrimitive(-1),new JsonPrimitive(3),new JsonPrimitive("1"),JsonNull.INSTANCE,new JsonPrimitive(true))) {
            JsonObject shape=point("STONE",null);shape.addProperty("shape","box");shape.add(endpoint,invalid);assertThrows(IllegalArgumentException.class,()->parse(shape),endpoint);
        }
        JsonObject reversed=point("STONE",null);reversed.addProperty("shape","box");reversed.addProperty("x",2);reversed.addProperty("x2",1);assertThrows(IllegalArgumentException.class,()->parse(reversed));
    }
    @Test void stateMustBeTypedCanonicalMatchingMaterialWithoutNbtOrDuplicateProperties() {
        for(String invalid:List.of("","spruce_slab[type=top]","other:spruce_slab[type=top]","minecraft:SPRUCE_SLAB[type=top]",
            "minecraft:spruce_slab[type=TOP]","minecraft:spruce_slab[Type=top]","minecraft:stone[type=top]",
            " minecraft:spruce_slab[type=top]","minecraft:spruce_slab[type=top] ","minecraft:spruce_slab[type =top]",
            "minecraft:spruce_slab[type=top, type=bottom]","minecraft:spruce_slab[type=top,type=bottom]",
            "minecraft:spruce_slab[type=top,waterlogged=true]",
            "minecraft:spruce_slab[]","minecraft:spruce_slab[type=]","minecraft:spruce_slab[type=top,]",
            "minecraft:spruce_slab{Command:\"say hi\"}","minecraft:spruce_slab[type=top]\n", "minecraft:spruce_slab[type=top]\u0000")) {
            assertThrows(IllegalArgumentException.class,()->parse(point("SPRUCE_SLAB",invalid)),invalid);
        }
        for(JsonElement invalid:List.of(new JsonPrimitive(1),new JsonPrimitive(true),JsonNull.INSTANCE,new JsonObject(),new JsonArray())) {
            JsonObject operation=point("SPRUCE_SLAB",null);operation.add("block_data",invalid);assertThrows(IllegalArgumentException.class,()->parse(operation));
        }
    }
    @Test void lengthLimitIsAppliedBeforeBukkitPropertySemantics() {
        String prefix="minecraft:spruce_slab[facing=";
        String atLimit=prefix+"a".repeat(512-prefix.length()-1)+"]";
        assertEquals(512,atLimit.length());assertEquals(atLimit,parse(point("SPRUCE_SLAB",atLimit)).blocks().getFirst().blockData());
        assertThrows(IllegalArgumentException.class,()->parse(point("SPRUCE_SLAB",prefix+"a".repeat(513-prefix.length()-1)+"]")));
        // Unknown Bukkit state properties remain semantic adapter checks, separate from this grammar.
        assertEquals("minecraft:spruce_slab[unknown_property=value]",parse(point("SPRUCE_SLAB","minecraft:spruce_slab[unknown_property=value]")).blocks().getFirst().blockData());
    }
    @Test void omitCannotCarryStateAndStateCannotBypassMaterialRestrictions() {
        for(JsonElement state:List.of(new JsonPrimitive("minecraft:stone"),new JsonPrimitive(""),JsonNull.INSTANCE)) {
            JsonObject plan=plan();plan.getAsJsonArray("operations").add(point("STONE",null));
            JsonObject omit=object("{\"shape\":\"box\",\"x\":1,\"y\":0,\"z\":0,\"mode\":\"omit\"}");omit.add("block_data",state);plan.getAsJsonArray("operations").add(omit);
            assertThrows(IllegalArgumentException.class,()->BuildSpec.parse(plan));
        }
        assertThrows(IllegalArgumentException.class,()->parse(point("TNT","minecraft:tnt[unstable=false]")));
        assertThrows(IllegalArgumentException.class,()->parse(point("STONE","minecraft:tnt[unstable=false]")));
    }
}
