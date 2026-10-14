package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CopySpecTest {
    private JsonObject input() { return JsonParser.parseString("{\"source\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"width\":3,\"height\":2,\"depth\":4},\"destination\":{\"x\":10,\"y\":70,\"z\":-10}}").getAsJsonObject(); }
    @Test void completeGeometryPreservesRelativeSourceDestinationMappingWithoutDuplicates() {
        JsonObject input=input(),before=input.deepCopy();CopySpec spec=CopySpec.parse(input);
        Set<CopySpec.Cell> expected=new HashSet<>();for(int x=0;x<3;x++)for(int y=0;y<2;y++)for(int z=0;z<4;z++)expected.add(new CopySpec.Cell(x,64+y,z,10+x,70+y,-10+z));
        assertEquals(expected.size(),spec.volume());assertEquals(expected.size(),spec.cells().size());assertEquals(expected,new HashSet<>(spec.cells()));
        assertEquals(before,input);assertThrows(UnsupportedOperationException.class,()->spec.cells().clear());
    }
    @Test void touchingVolumesAreAllowedWhileEveryActualOverlapRefuses() {
        for(int[] destination:List.of(new int[]{3,64,0},new int[]{0,66,0},new int[]{0,64,4})) {
            JsonObject input=input(),target=input.getAsJsonObject("destination");target.addProperty("x",destination[0]);target.addProperty("y",destination[1]);target.addProperty("z",destination[2]);assertDoesNotThrow(()->CopySpec.parse(input));
        }
        for(int[] destination:List.of(new int[]{0,64,0},new int[]{2,65,3},new int[]{-2,63,-3})) {
            JsonObject input=input(),target=input.getAsJsonObject("destination");target.addProperty("x",destination[0]);target.addProperty("y",destination[1]);target.addProperty("z",destination[2]);assertThrows(IllegalArgumentException.class,()->CopySpec.parse(input));
        }
    }
    @Test void maximumVolumeAndAllDimensionLimitsApplyBeforeEnumeratingCells() {
        JsonObject input=input();JsonObject source=input.getAsJsonObject("source");source.addProperty("width",32);source.addProperty("height",16);source.addProperty("depth",16);input.getAsJsonObject("destination").addProperty("x",64);
        assertEquals(8192,CopySpec.parse(input).volume());assertEquals(8192,CopySpec.parse(input).cells().size());
        source.addProperty("height",17);assertThrows(IllegalArgumentException.class,()->CopySpec.parse(input));
        for(String field:List.of("width","height","depth"))for(int value:List.of(0,-1,33)) {
            JsonObject invalid=input();invalid.getAsJsonObject("source").addProperty(field,value);assertThrows(IllegalArgumentException.class,()->CopySpec.parse(invalid),field);
        }
    }
    @Test void bothFullVolumesRespectWorldExtentsAndIntegerOverflowCannotWrap() {
        for(String side:List.of("source","destination"))for(String coordinate:List.of("x","y","z"))for(int value:List.of(Integer.MIN_VALUE,Integer.MAX_VALUE)) {
            JsonObject invalid=input();invalid.getAsJsonObject(side).addProperty(coordinate,value);assertThrows(IllegalArgumentException.class,()->CopySpec.parse(invalid),side+"."+coordinate);
        }
        for(String side:List.of("source","destination"))for(String coordinate:List.of("x","z")) {
            JsonObject invalid=input();invalid.getAsJsonObject(side).addProperty(coordinate,29_999_984);assertThrows(IllegalArgumentException.class,()->CopySpec.parse(invalid));
        }
        for(String side:List.of("source","destination")) {
            JsonObject invalid=input();invalid.getAsJsonObject(side).addProperty("y",2048);assertThrows(IllegalArgumentException.class,()->CopySpec.parse(invalid));
        }
    }
    @Test void parserRequiresExactIntegersNestedShapesAndOnlyDocumentedKeys() {
        for(String side:List.of("source","destination"))for(String coordinate:List.of("x","y","z"))for(JsonElement value:List.of(new JsonPrimitive("1"),new JsonPrimitive(1.5),new JsonPrimitive(true),JsonNull.INSTANCE)) {
            JsonObject invalid=input();invalid.getAsJsonObject(side).add(coordinate,value);assertThrows(IllegalArgumentException.class,()->CopySpec.parse(invalid));
        }
        for(String side:List.of("source","destination"))for(JsonElement value:List.of(new JsonPrimitive("world"),new JsonArray(),JsonNull.INSTANCE)) {
            JsonObject invalid=input();invalid.add(side,value);assertThrows(IllegalArgumentException.class,()->CopySpec.parse(invalid));
        }
        JsonObject extra=input();extra.addProperty("replace_existing",true);assertThrows(IllegalArgumentException.class,()->CopySpec.parse(extra));
        JsonObject otherWorld=input();otherWorld.getAsJsonObject("destination").addProperty("world","other");assertThrows(IllegalArgumentException.class,()->CopySpec.parse(otherWorld));
    }
}
