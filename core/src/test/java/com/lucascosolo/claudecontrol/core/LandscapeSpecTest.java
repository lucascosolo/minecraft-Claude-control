package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class LandscapeSpecTest {
    private JsonObject operation(int x,String expected,String replacement) {
        JsonObject input=new JsonObject();input.addProperty("x",x);input.addProperty("y",64);input.addProperty("z",0);
        input.addProperty("expected_material",expected);input.addProperty("material",replacement);return input;
    }
    private JsonObject input(int count) {
        JsonObject input=new JsonObject();input.addProperty("world","world");input.addProperty("survey_id",UUID.randomUUID().toString());
        JsonArray operations=new JsonArray();for(int x=0;x<count;x++)operations.add(operation(x,"DIRT","AIR"));input.add("operations",operations);return input;
    }
    private LandscapeSpec.Snapshot safe(String material) { return new LandscapeSpec.Snapshot(material,false,false,true); }
    @Test void acceptsExactSurveyedNaturalRemovalAndFillWithImmutableCompleteBatch() {
        JsonObject input=input(2);input.getAsJsonArray("operations").set(1,operation(1,"AIR","DIRT"));
        JsonObject before=input.deepCopy();LandscapeSpec spec=LandscapeSpec.parse(input);List<String> reads=new ArrayList<>();
        List<LandscapeSpec.Edit> edits=spec.preflight(edit->{reads.add("survey-"+edit.x());return safe(edit.expectedMaterial());},
                                                    edit->{reads.add("live-"+edit.x());return safe(edit.expectedMaterial());});
        assertEquals(spec.edits(),edits);assertEquals(before,input);assertEquals(4,reads.size());
        assertEquals(Set.of("survey-0","live-0","survey-1","live-1"),new HashSet<>(reads));
        assertEquals("AIR",edits.get(0).material());assertEquals("DIRT",edits.get(1).material());
        assertThrows(UnsupportedOperationException.class,()->edits.clear());
        input.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("material","TNT");
        assertEquals("AIR",spec.edits().get(0).material());
    }
    @Test void staleLastCellPreventsEveryWriteRatherThanReturningAnApprovedPrefix() {
        LandscapeSpec spec=LandscapeSpec.parse(input(3));AtomicInteger liveReads=new AtomicInteger(),writes=new AtomicInteger();
        Function<LandscapeSpec.Edit,LandscapeSpec.Snapshot> live=edit->{liveReads.incrementAndGet();return safe(edit.x()==2?"STONE":"DIRT");};
        assertThrows(IllegalArgumentException.class,()->{
            List<LandscapeSpec.Edit> wholeBatch=spec.preflight(edit->safe("DIRT"),live);
            for(var edit:wholeBatch)writes.incrementAndGet();
        });
        assertEquals(3,liveReads.get());assertEquals(0,writes.get());
    }
    @Test void staleSurveyCannotBeReplacedWithFreshLiveObservation() {
        LandscapeSpec spec=LandscapeSpec.parse(input(2));
        assertThrows(IllegalArgumentException.class,()->spec.preflight(edit->safe(edit.x()==1?"STONE":"DIRT"),edit->safe("DIRT")));
        assertThrows(IllegalArgumentException.class,()->spec.preflight(edit->null,edit->safe("DIRT")));
        assertThrows(IllegalArgumentException.class,()->spec.preflight(edit->safe("DIRT"),edit->null));
        assertThrows(IllegalArgumentException.class,()->spec.preflight(null,edit->safe("DIRT")));
        assertThrows(IllegalArgumentException.class,()->spec.preflight(edit->safe("DIRT"),null));
    }
    @Test void protectedTileStatesLivingEntitiesAndUnloadedCellsRefuseEntireBatch() {
        LandscapeSpec spec=LandscapeSpec.parse(input(2));
        for(var blocked:List.of(new LandscapeSpec.Snapshot("DIRT",true,false,true),
                                new LandscapeSpec.Snapshot("DIRT",false,true,true),
                                new LandscapeSpec.Snapshot("DIRT",false,false,false))) {
            assertThrows(IllegalArgumentException.class,()->spec.preflight(edit->edit.x()==1?blocked:safe("DIRT"),edit->safe("DIRT")));
            assertThrows(IllegalArgumentException.class,()->spec.preflight(edit->safe("DIRT"),edit->edit.x()==1?blocked:safe("DIRT")));
        }
    }
    @Test void artificialBuildsContainersAndHazardsCannotBeExpectedOrReplacementMaterials() {
        for(String material:List.of("CHEST","BARREL","OAK_PLANKS","GLASS","STONE_BRICKS","COMMAND_BLOCK","TNT","LAVA","WATER")) {
            JsonObject expected=input(1);expected.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("expected_material",material);
            assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(expected),material);
            JsonObject replacement=input(1);replacement.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("material",material);
            assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(replacement),material);
            assertFalse(LandscapeSpec.natural(material));
        }
        assertFalse(LandscapeSpec.natural(null));
        LandscapeSpec spec=LandscapeSpec.parse(input(1));
        assertThrows(IllegalArgumentException.class,()->spec.preflight(edit->safe("DIRT"),edit->safe("OAK_PLANKS")));
    }
    @Test void operationsAreBoundedUniqueAndStrictlyTypedWithoutCoercion() {
        assertEquals(512,LandscapeSpec.parse(input(512)).edits().size());
        assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(input(0)));
        assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(input(513)));
        JsonObject duplicate=input(2);duplicate.getAsJsonArray("operations").set(1,duplicate.getAsJsonArray("operations").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(duplicate));
        for(String coordinate:List.of("x","y","z"))for(JsonElement value:List.of(new JsonPrimitive("1"),new JsonPrimitive(1.5),new JsonPrimitive(true),JsonNull.INSTANCE,new JsonArray())) {
            JsonObject invalid=input(1);invalid.getAsJsonArray("operations").get(0).getAsJsonObject().add(coordinate,value);
            assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(invalid),coordinate);
        }
        for(String field:List.of("x","y","z","expected_material","material")) {
            JsonObject missing=input(1);missing.getAsJsonArray("operations").get(0).getAsJsonObject().remove(field);
            assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(missing),field);
        }
        for(String coordinate:List.of("x","y","z")) {
            JsonObject far=input(1);far.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty(coordinate,Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(far),coordinate);
        }
    }
    @Test void worldSurveyIdentityAndOperationShapesFailClosed() {
        for(String field:List.of("world","survey_id"))for(JsonElement value:List.of(new JsonPrimitive(""),new JsonPrimitive(1),new JsonPrimitive(true),JsonNull.INSTANCE)) {
            JsonObject invalid=input(1);invalid.add(field,value);assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(invalid),field);
        }
        JsonObject badId=input(1);badId.addProperty("survey_id","stale-unverified");assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(badId));
        for(JsonElement operation:List.of(JsonNull.INSTANCE,new JsonPrimitive("DIRT"),new JsonArray())) {
            JsonObject invalid=input(1);invalid.getAsJsonArray("operations").set(0,operation);assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(invalid));
        }
        JsonObject unknown=input(1);unknown.addProperty("replace_existing",true);assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(unknown));
        JsonObject metadata=input(1);metadata.getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("force",true);
        assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(metadata));assertThrows(IllegalArgumentException.class,()->LandscapeSpec.parse(null));
    }
}
