package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SurveyLayersTest {
    private static final String LEGEND="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+-";
    private List<String> rows(JsonObject encoded,int layer) {
        List<String> result=new ArrayList<>();for(JsonElement row:encoded.getAsJsonArray("rows").get(layer).getAsJsonArray())result.add(row.getAsString());return result;
    }
    @Test void convertsXThenYThenZSourceOrderIntoZRowsOfXColumnsForEachLayer() {
        List<Integer> indices=IntStream.range(0,12).boxed().toList();JsonObject encoded=SurveyLayers.encode(2,2,3,indices);
        assertEquals(LEGEND,encoded.get("legend").getAsString());assertEquals(2,encoded.getAsJsonArray("rows").size());
        assertEquals(List.of("06","17","28"),rows(encoded,0));assertEquals(List.of("39","4A","5B"),rows(encoded,1));
        assertEquals(IntStream.range(0,12).boxed().toList(),indices);
    }
    @Test void angledFootprintRowsShowActualShapeWithoutTransposingAxes() {
        JsonObject encoded=SurveyLayers.encode(3,1,3,List.of(1,0,0,1,1,0,1,1,1));
        assertEquals(List.of("111","011","001"),rows(encoded,0));
    }
    @Test void fullPaletteIndicesMapToTheirExactLegendSymbolsWithoutLoss() {
        JsonObject encoded=SurveyLayers.encode(32,1,2,IntStream.range(0,64).boxed().toList());
        List<String> rows=rows(encoded,0);assertEquals(2,rows.size());
        Set<Character> seen=new HashSet<>();
        for(int x=0;x<32;x++) {
            assertEquals(LEGEND.charAt(x*2),rows.get(0).charAt(x));assertEquals(LEGEND.charAt(x*2+1),rows.get(1).charAt(x));
            seen.add(rows.get(0).charAt(x));seen.add(rows.get(1).charAt(x));
        }
        assertEquals(LEGEND.length(),seen.size());
    }
    @Test void maximumCellCountAndEightVerticalLayersRemainComplete() {
        JsonObject encoded=SurveyLayers.encode(16,8,16,Collections.nCopies(2048,63));assertEquals(8,encoded.getAsJsonArray("rows").size());
        int cells=0;for(int layer=0;layer<8;layer++) {
            List<String> rows=rows(encoded,layer);assertEquals(16,rows.size());for(String row:rows){assertEquals("-".repeat(16),row);cells+=row.length();}
        }
        assertEquals(2048,cells);
    }
    @Test void dimensionsCountsAndInvalidPaletteEntriesFailClosed() {
        for(int[] size:List.of(new int[]{0,1,1},new int[]{1,0,1},new int[]{1,1,0},new int[]{-1,1,1},
            new int[]{33,1,1},new int[]{1,9,1},new int[]{1,1,33},new int[]{32,8,32},new int[]{Integer.MAX_VALUE,8,Integer.MAX_VALUE})) {
            assertThrows(IllegalArgumentException.class,()->SurveyLayers.encode(size[0],size[1],size[2],List.of()));
        }
        assertThrows(IllegalArgumentException.class,()->SurveyLayers.encode(2,1,1,List.of(0)));
        assertThrows(IllegalArgumentException.class,()->SurveyLayers.encode(1,1,1,List.of(0,1)));
        for(Integer index:Arrays.asList(-1,64,Integer.MAX_VALUE,null)) {
            List<Integer> invalid=Collections.singletonList(index);assertThrows(IllegalArgumentException.class,()->SurveyLayers.encode(1,1,1,invalid));
        }
        assertThrows(IllegalArgumentException.class,()->SurveyLayers.encode(1,1,1,null));
    }
}
