package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurveySpecTest {
    private JsonObject input() {
        return JsonParser.parseString("{\"world\":\"world\",\"x\":10,\"y\":60,\"z\":-4,\"width\":3,\"height\":2,\"depth\":4}").getAsJsonObject();
    }
    @Test void enumeratesEveryFootprintCellExactlyOnceIncludingInteriorAndEdges() {
        JsonObject input=input(),before=input.deepCopy();SurveySpec spec=SurveySpec.parse(input);
        Set<SurveySpec.Cell> expected=new HashSet<>();
        for(int x=10;x<13;x++)for(int y=60;y<62;y++)for(int z=-4;z<0;z++)expected.add(new SurveySpec.Cell(x,y,z));
        List<SurveySpec.Cell> cells=spec.cells();
        assertEquals(expected.size(),cells.size());assertEquals(expected,new HashSet<>(cells));assertEquals(before,input);
        assertEquals("world",spec.world());
        assertThrows(UnsupportedOperationException.class,()->cells.add(new SurveySpec.Cell(100,100,100)));
    }
    @Test void fullMaximumVolumeIsEnumeratedAndLargerDimensionsOrVolumesRefuse() {
        JsonObject input=input();input.addProperty("width",32);input.addProperty("height",16);input.addProperty("depth",16);
        var cells=SurveySpec.parse(input).cells();assertEquals(8192,cells.size());assertEquals(8192,new HashSet<>(cells).size());
        input.addProperty("height",17);assertThrows(IllegalArgumentException.class,()->SurveySpec.parse(input));
        for(String field:List.of("width","height","depth"))for(int value:List.of(0,-1,33)) {
            JsonObject invalid=input();invalid.addProperty(field,value);
            assertThrows(IllegalArgumentException.class,()->SurveySpec.parse(invalid),field+"="+value);
        }
    }
    @Test void dimensionsAndCoordinatesRequireActualFiniteIntegers() {
        for(String field:List.of("x","y","z","width","height","depth")) {
            for(JsonElement value:List.of(new JsonPrimitive("1"),new JsonPrimitive(1.5),new JsonPrimitive(true),JsonNull.INSTANCE,new JsonArray())) {
                JsonObject invalid=input();invalid.add(field,value);
                assertThrows(IllegalArgumentException.class,()->SurveySpec.parse(invalid),field);
            }
            JsonObject missing=input();missing.remove(field);assertThrows(IllegalArgumentException.class,()->SurveySpec.parse(missing),field);
        }
    }
    @Test void malformedWorldAndCoordinateOverflowFailClosedBeforeEnumeration() {
        for(JsonElement value:List.of(new JsonPrimitive(""),new JsonPrimitive(42),new JsonPrimitive(true),JsonNull.INSTANCE)) {
            JsonObject invalid=input();invalid.add("world",value);assertThrows(IllegalArgumentException.class,()->SurveySpec.parse(invalid));
        }
        for(String field:List.of("x","y","z")) {
            JsonObject invalid=input();invalid.addProperty(field,Integer.MAX_VALUE);assertThrows(IllegalArgumentException.class,()->SurveySpec.parse(invalid));
        }
        JsonObject unknown=input();unknown.addProperty("load_chunks",true);assertThrows(IllegalArgumentException.class,()->SurveySpec.parse(unknown));
        assertThrows(IllegalArgumentException.class,()->SurveySpec.parse(null));
    }
}
