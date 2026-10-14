package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectDraftTest {
    private Path scratch() throws IOException {
        Path base=Path.of("build","test-scratch").toAbsolutePath();Files.createDirectories(base);return Files.createTempDirectory(base,"project-draft-");
    }
    private JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private JsonObject header(String id) {
        JsonObject header=object("{\"title\":\"Mansion\",\"goal\":\"A coherent shared mansion\",\"layout\":\"North hall and garden beside the road\",\"steps\":[]}");header.addProperty("id",id);return header;
    }
    private JsonArray steps(int from,int count) {
        JsonArray steps=new JsonArray();for(int i=from;i<from+count;i++) {
            JsonObject step=object("{\"description\":\"Inspect before the next stage\",\"tool\":\"observe_world\",\"args\":{}}");step.addProperty("id","step-"+i);steps.add(step);
        }return steps;
    }
    private void create(ProjectStore store,String id) throws IOException { store.create(header(id),UUID.randomUUID(),"Owner"); }
    private void assertPlanning(JsonObject record,int stepCount) {
        assertEquals("planning",record.get("state").getAsString());assertFalse(record.get("planning_complete").getAsBoolean());
        assertEquals(stepCount,record.getAsJsonObject("definition").getAsJsonArray("steps").size());
    }
    @Test void emptyDraftIsDurableButCannotStartSealOrExecute() throws Exception {
        Path root=scratch();ProjectStore store=new ProjectStore(root);UUID owner=UUID.randomUUID();
        JsonObject input=header("mansion"),created=store.create(input,owner,"OriginalOwner");assertPlanning(created,0);
        assertEquals(owner.toString(),created.get("owner_id").getAsString());assertEquals("OriginalOwner",created.get("owner_name").getAsString());
        input.addProperty("goal","mutated input");created.getAsJsonObject("definition").addProperty("layout","mutated result");
        ProjectStore restored=new ProjectStore(root);JsonObject record=restored.read("mansion");assertPlanning(record,0);
        assertEquals("A coherent shared mansion",record.getAsJsonObject("definition").get("goal").getAsString());
        assertEquals("North hall and garden beside the road",record.getAsJsonObject("definition").get("layout").getAsString());
        assertThrows(IOException.class,()->restored.start("mansion"));assertThrows(IOException.class,()->restored.seal("mansion"));
        assertThrows(IOException.class,()->restored.beginStep("mansion"));
        assertEquals(record,restored.read("mansion"));
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(header("empty")));
        assertThrows(IllegalArgumentException.class,()->store.save(header("empty"),owner,"Owner"));
    }
    @Test void appendPersistsDistinctStagesWithoutChangingEarlierHeaderOrSteps() throws Exception {
        Path root=scratch();ProjectStore store=new ProjectStore(root);create(store,"mansion");
        JsonArray first=steps(0,2);JsonObject result=store.append("mansion",first);assertPlanning(result,2);
        JsonObject firstStored=result.getAsJsonObject("definition").getAsJsonArray("steps").get(0).getAsJsonObject().deepCopy();
        first.get(0).getAsJsonObject().addProperty("description","mutated input");
        result.getAsJsonObject("definition").getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("description","mutated result");
        store.append("mansion",steps(2,2));JsonObject restored=new ProjectStore(root).read("mansion");assertPlanning(restored,4);
        assertEquals(firstStored,restored.getAsJsonObject("definition").getAsJsonArray("steps").get(0));
        assertEquals("A coherent shared mansion",restored.getAsJsonObject("definition").get("goal").getAsString());
        assertEquals("North hall and garden beside the road",restored.getAsJsonObject("definition").get("layout").getAsString());
        assertEquals(4,restored.getAsJsonArray("step_states").size());
    }
    @Test void invalidAppendNeverPartiallyAddsStepsAndByteBudgetUsesUtf8() throws Exception {
        ProjectStore store=new ProjectStore(scratch());create(store,"mansion");store.append("mansion",steps(0,1));JsonObject before=store.read("mansion");
        for(JsonArray invalid:List.of(new JsonArray(),steps(1,5),steps(0,1))) {
            assertThrows(IllegalArgumentException.class,()->store.append("mansion",invalid));assertEquals(before,store.read("mansion"));
        }
        JsonArray duplicateWithin=steps(1,2);duplicateWithin.get(1).getAsJsonObject().addProperty("id","step-1");
        assertThrows(IllegalArgumentException.class,()->store.append("mansion",duplicateWithin));
        JsonArray unicode=steps(1,1);JsonObject step=unicode.get(0).getAsJsonObject();step.addProperty("tool","memory");
        JsonObject args=object("{\"action\":\"write\",\"name\":\"mansion.md\"}");args.addProperty("text","é".repeat(3000));step.add("args",args);
        assertTrue(unicode.toString().length()<6000);assertTrue(unicode.toString().getBytes(StandardCharsets.UTF_8).length>6000);
        assertThrows(IllegalArgumentException.class,()->store.append("mansion",unicode));assertEquals(before,store.read("mansion"));
    }
    @Test void sealingMakesOnlyNonemptyPlansEligibleForOrderedExecution() throws Exception {
        ProjectStore store=new ProjectStore(scratch());create(store,"mansion");store.append("mansion",steps(0,2));
        JsonObject sealed=store.seal("mansion");assertEquals("saved",sealed.get("state").getAsString());assertTrue(sealed.get("planning_complete").getAsBoolean());
        assertEquals("active",store.start("mansion").get("state").getAsString());
        assertEquals("step-0",store.beginStep("mansion").get("id").getAsString());
    }
    @Test void appendAndResealRefuseOnceAPlanLeavesPlanning() throws Exception {
        for(String state:List.of("saved","active","complete","blocked")) {
            ProjectStore store=new ProjectStore(scratch());create(store,"mansion");store.append("mansion",steps(0,1));store.seal("mansion");
            if(!state.equals("saved"))store.start("mansion");
            if(Set.of("complete","blocked").contains(state)){store.beginStep("mansion");store.finishStep("mansion","step-0",state.equals("complete"),"Actual execution receipt");}
            JsonObject before=store.read("mansion");assertEquals(state,before.get("state").getAsString());
            assertThrows(IOException.class,()->store.append("mansion",steps(1,1)));assertThrows(IOException.class,()->store.seal("mansion"));
            assertEquals(before,store.read("mansion"));
        }
    }
    @Test void incrementalPlansKeepGlobalStepAndExpandedPlacementLimits() throws Exception {
        ProjectStore store=new ProjectStore(scratch());create(store,"mansion");for(int i=0;i<32;i+=4)store.append("mansion",steps(i,4));
        JsonObject full=store.read("mansion");assertPlanning(full,32);assertThrows(IllegalArgumentException.class,()->store.append("mansion",steps(32,1)));
        assertEquals(full,store.read("mansion"));
        ProjectStore builds=new ProjectStore(scratch());create(builds,"large");JsonArray large=steps(0,4);
        for(JsonElement element:large) {
            JsonObject step=element.getAsJsonObject();step.addProperty("tool","build");step.add("args",object("{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"width\":32,\"height\":16,\"depth\":16,\"operations\":[{\"shape\":\"box\",\"x\":0,\"y\":0,\"z\":0,\"x2\":31,\"y2\":15,\"z2\":15,\"material\":\"STONE\"}]}"));
        }
        builds.append("large",large);JsonObject maximum=builds.read("large");JsonArray overflow=new JsonArray();
        JsonObject extra=large.get(0).getAsJsonObject().deepCopy();extra.addProperty("id","overflow");overflow.add(extra);
        assertThrows(IllegalArgumentException.class,()->builds.append("large",overflow));assertEquals(maximum,builds.read("large"));
    }
    @Test void pagedViewsRetainEveryFutureStageAndSingleDetailsAreActualStoredSteps() throws Exception {
        ProjectStore store=new ProjectStore(scratch());create(store,"mansion");for(int i=0;i<12;i+=4)store.append("mansion",steps(i,4));
        List<String> ids=new ArrayList<>();int offset=0;
        do {
            JsonObject page=store.view("mansion",null,offset,4);assertTrue(page.toString().getBytes(StandardCharsets.UTF_8).length<=7500);
            assertEquals(12,page.get("total_steps").getAsInt());assertEquals(offset,page.get("offset").getAsInt());assertTrue(page.getAsJsonArray("steps").size()<=4);
            for(JsonElement element:page.getAsJsonArray("steps"))ids.add(element.getAsJsonObject().get("id").getAsString());
            if(page.get("next_offset").isJsonNull())break;
            int next=page.get("next_offset").getAsInt();assertTrue(next>offset);offset=next;
        }while(ids.size()<=12);
        List<String> expected=new ArrayList<>();for(int i=0;i<12;i++)expected.add("step-"+i);assertEquals(expected,ids);
        JsonObject detail=store.view("mansion","step-9",0,4);
        assertEquals(store.read("mansion").getAsJsonObject("definition").getAsJsonArray("steps").get(9),detail.getAsJsonObject("step"));
        assertEquals("pending",detail.getAsJsonObject("step_status").get("state").getAsString());
        assertFalse(detail.has("steps"));assertTrue(detail.toString().getBytes(StandardCharsets.UTF_8).length<=7500);
        assertThrows(IOException.class,()->store.view("mansion","missing",0,4));
        assertThrows(IllegalArgumentException.class,()->store.view("mansion",null,-1,4));
        for(int limit:List.of(0,5))assertThrows(IllegalArgumentException.class,()->store.view("mansion",null,0,limit));
    }
    @Test void oversizedSingleDetailRefusesInsteadOfTruncatingExecutableStepArguments() throws Exception {
        ProjectStore store=new ProjectStore(scratch());JsonObject header=header("large-detail");
        header.addProperty("goal","g".repeat(4096));header.addProperty("layout","l".repeat(8192));store.create(header,UUID.randomUUID(),"Owner");
        JsonArray batch=steps(0,1);JsonObject step=batch.get(0).getAsJsonObject();step.addProperty("tool","memory");
        JsonObject args=object("{\"action\":\"write\",\"name\":\"mansion.md\"}");args.addProperty("text","x".repeat(4500));step.add("args",args);
        assertTrue(batch.toString().getBytes(StandardCharsets.UTF_8).length<=6000);store.append("large-detail",batch);
        JsonObject before=store.read("large-detail");JsonObject summary=store.view("large-detail",null,0,4);
        assertEquals(1,summary.getAsJsonArray("steps").size());assertTrue(summary.toString().getBytes(StandardCharsets.UTF_8).length<=7500);
        assertThrows(IOException.class,()->store.view("large-detail","step-0",0,4));assertEquals(before,store.read("large-detail"));
        assertEquals("x".repeat(4500),store.read("large-detail").getAsJsonObject("definition").getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("args").get("text").getAsString());
    }
    @Test void oldInterruptedRecordsRemainInspectionOnlyWithoutAutomaticReplay() throws Exception {
        Path root=scratch();ProjectStore store=new ProjectStore(root);JsonObject definition=header("legacy");definition.add("steps",steps(0,1));store.save(definition,UUID.randomUUID(),"Owner");store.start("legacy");store.beginStep("legacy");
        JsonObject old=store.read("legacy");old.remove("planning_complete");
        Files.move(root.resolve("legacy.json"),root.resolve(".preserved-pre-legacy-fixture.json"));Files.writeString(root.resolve("legacy.json"),old.toString());
        ProjectStore restored=new ProjectStore(root);JsonObject interrupted=restored.read("legacy");assertEquals("needs_inspection",interrupted.get("state").getAsString());
        assertThrows(IOException.class,()->restored.beginStep("legacy"));assertThrows(IOException.class,()->restored.resume("legacy"));assertThrows(IOException.class,()->restored.append("legacy",steps(1,1)));
    }
}
