package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectStoreTest {
    private JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private Path scratch() throws IOException {
        Path base=Path.of("build","test-scratch").toAbsolutePath();Files.createDirectories(base);
        return Files.createTempDirectory(base,"project-store-");
    }
    private JsonObject definition(String id,int count) {
        JsonObject definition=object("{\"title\":\"Town hall\",\"goal\":\"Build a shared meeting place\",\"layout\":\"Hall beside the village road\",\"steps\":[]}");
        definition.addProperty("id",id);
        for(int i=0;i<count;i++)definition.getAsJsonArray("steps").add(step("step-"+i,"observe_world",new JsonObject()));
        return definition;
    }
    private JsonObject step(String id,String tool,JsonObject args) {
        JsonObject step=object("{\"description\":\"Inspect current conditions\"}");
        step.addProperty("id",id);step.addProperty("tool",tool);step.add("args",args);return step;
    }
    private JsonObject status(JsonObject record,int index) { return record.getAsJsonArray("step_states").get(index).getAsJsonObject(); }
    private void save(ProjectStore store,JsonObject definition) throws IOException { store.save(definition,UUID.randomUUID(),"Owner"); }
    private JsonObject build() {
        return object("{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"width\":32,\"height\":16,\"depth\":16,\"operations\":[{\"shape\":\"box\",\"x\":0,\"y\":0,\"z\":0,\"x2\":31,\"y2\":15,\"z2\":15,\"mode\":\"solid\",\"material\":\"STONE\"}]}");
    }
    @Test void savedDefinitionsOwnersAndReadSnapshotsAreImmutableAndDurable() throws Exception {
        Path root=scratch();ProjectStore store=new ProjectStore(root);UUID owner=UUID.randomUUID();
        JsonObject definition=definition("town-hall",2);
        JsonObject saved=store.save(definition,owner,"TownOwner");
        assertEquals("saved",saved.get("state").getAsString());
        assertEquals(owner.toString(),saved.get("owner_id").getAsString());
        assertEquals("TownOwner",saved.get("owner_name").getAsString());
        assertEquals("pending",status(saved,0).get("state").getAsString());
        definition.addProperty("goal","mutated input");saved.getAsJsonObject("definition").addProperty("layout","mutated result");
        JsonObject read=store.read("town-hall");read.getAsJsonObject("definition").addProperty("title","mutated read");
        JsonObject durable=new ProjectStore(root).read("town-hall");
        assertEquals("Build a shared meeting place",durable.getAsJsonObject("definition").get("goal").getAsString());
        assertEquals("Hall beside the village road",durable.getAsJsonObject("definition").get("layout").getAsString());
        assertEquals("Town hall",durable.getAsJsonObject("definition").get("title").getAsString());
        assertThrows(IOException.class,()->store.save(definition,UUID.randomUUID(),"Replacement"));
        assertEquals(durable,store.read("town-hall"));
    }
    @Test void runningIsPersistedBeforeStepReturnsAndReloadRequiresInspectionWithoutReplay() throws Exception {
        Path root=scratch();ProjectStore store=new ProjectStore(root);save(store,definition("hall",2));store.start("hall");
        JsonObject first=store.beginStep("hall");assertEquals("step-0",first.get("id").getAsString());
        assertEquals("running",status(JsonParser.parseString(Files.readString(root.resolve("hall.json"))).getAsJsonObject(),0).get("state").getAsString());
        assertThrows(IOException.class,()->store.beginStep("hall"));
        ProjectStore recovered=new ProjectStore(root);JsonObject interrupted=recovered.read("hall");
        assertEquals("needs_inspection",interrupted.get("state").getAsString());
        assertEquals("needs_inspection",status(interrupted,0).get("state").getAsString());
        assertEquals("pending",status(interrupted,1).get("state").getAsString());
        assertThrows(IOException.class,()->recovered.resume("hall"));
        assertThrows(IOException.class,()->recovered.start("hall"));
        assertThrows(IOException.class,()->recovered.beginStep("hall"));
        assertEquals(interrupted,new ProjectStore(root).read("hall"));
    }
    @Test void actualReceiptsAdvanceOrderedStepsAndCompletionSurvivesReload() throws Exception {
        Path root=scratch();ProjectStore store=new ProjectStore(root);save(store,definition("hall",2));
        assertThrows(IOException.class,()->store.finishStep("hall","step-0",true,"invented before execution"));
        store.start("hall");store.beginStep("hall");
        assertThrows(IOException.class,()->store.finishStep("hall","step-1",true,"wrong step"));
        assertThrows(IOException.class,()->store.finishStep("hall","step-0",true,null));
        store.finishStep("hall","step-0",true,"Observed loaded road and footprint");
        assertThrows(IOException.class,()->store.finishStep("hall","step-0",true,"repeated receipt"));
        assertEquals("step-1",store.beginStep("hall").get("id").getAsString());
        JsonObject finished=store.finishStep("hall","step-1",true,"Second observation succeeded");
        assertEquals("complete",finished.get("state").getAsString());
        assertEquals("Observed loaded road and footprint",status(finished,0).get("receipt").getAsString());
        assertEquals(finished,new ProjectStore(root).read("hall"));
        assertThrows(IOException.class,()->store.beginStep("hall"));
    }
    @Test void pauseStopsPendingExecutionAndFailedReceiptBlocksAutomaticReplay() throws Exception {
        ProjectStore store=new ProjectStore(scratch());save(store,definition("hall",2));store.start("hall");
        assertEquals("paused",store.pause("hall").get("state").getAsString());
        assertThrows(IOException.class,()->store.beginStep("hall"));store.resume("hall");store.beginStep("hall");
        JsonObject failed=store.finishStep("hall","step-0",false,"Loaded ground obstructed; no placements made");
        assertEquals("blocked",failed.get("state").getAsString());assertEquals("blocked",status(failed,0).get("state").getAsString());
        assertEquals("pending",status(failed,1).get("state").getAsString());
        assertThrows(IOException.class,()->store.resume("hall"));assertThrows(IOException.class,()->store.beginStep("hall"));
    }
    @Test void receiptLengthIsBoundedAndControlCharactersDoNotEnterLedger() throws Exception {
        ProjectStore store=new ProjectStore(scratch());save(store,definition("hall",1));store.start("hall");store.beginStep("hall");
        JsonObject finished=store.finishStep("hall","step-0",true,"Actual result\u0000\n"+"x".repeat(2500));
        String receipt=status(finished,0).get("receipt").getAsString();
        assertTrue(receipt.length()<=2000);assertFalse(receipt.contains("\u0000"));assertTrue(receipt.startsWith("Actual result\n"));
    }
    @Test void validatesToolAllowlistStrictStepShapesAndNoMemoryArchival() {
        for(String tool:List.of("run_commands","teleport_player","world_effect","give_item","companion","unknown")) {
            JsonObject input=definition("hall",1);input.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("tool",tool);
            assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(input),tool);
        }
        JsonObject archive=definition("hall",1);archive.getAsJsonArray("steps").set(0,step("archive","memory",object("{\"action\":\"archive\",\"name\":\"town.md\"}")));
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(archive));
        for(String missing:List.of("id","description","tool","args")) {
            JsonObject input=definition("hall",1);input.getAsJsonArray("steps").get(0).getAsJsonObject().remove(missing);
            assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(input),missing);
        }
        JsonObject badArgs=definition("hall",1);badArgs.getAsJsonArray("steps").get(0).getAsJsonObject().add("args",JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(badArgs));
        JsonObject duplicate=definition("hall",2);duplicate.getAsJsonArray("steps").get(1).getAsJsonObject().addProperty("id","step-0");
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(duplicate));
        JsonObject valid=definition("hall",1),copy=ProjectStore.validateDefinition(valid);copy.addProperty("goal","changed");assertNotEquals(copy,valid);
    }
    @Test void limitsProjectsStepsAndExpandedPlacementBudget() throws Exception {
        ProjectStore store=new ProjectStore(scratch());for(int i=7;i>=0;i--)save(store,definition("project-"+i,1));
        assertEquals(List.of("project-0","project-1","project-2","project-3","project-4","project-5","project-6","project-7"),store.list());
        assertThrows(IOException.class,()->save(store,definition("overflow",1)));assertEquals(8,store.list().size());
        assertDoesNotThrow(()->ProjectStore.validateDefinition(definition("thirty-two",32)));
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(definition("none",0)));
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(definition("too-many",33)));
        JsonObject large=definition("large",0);for(int i=0;i<4;i++)large.getAsJsonArray("steps").add(step("build-"+i,"build",build()));
        assertDoesNotThrow(()->ProjectStore.validateDefinition(large));
        large.getAsJsonArray("steps").add(step("overflow","build",build()));
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(large));
        JsonObject physical=definition("physical",0);physical.getAsJsonArray("steps").add(step("walk-build","build_in_character",build()));
        assertThrows(IllegalArgumentException.class,()->ProjectStore.validateDefinition(physical));
    }
    @Test void symlinkLedgerRootAndFilesRefuseWithoutChangingCanaries() throws Exception {
        Path fixture=scratch(),real=Files.createDirectory(fixture.resolve("real"));
        ProjectStore store=new ProjectStore(real);save(store,definition("hall",1));byte[] before=Files.readAllBytes(real.resolve("hall.json"));
        Path link=Files.createSymbolicLink(fixture.resolve("linked"),real);
        assertThrows(IOException.class,()->new ProjectStore(link));
        Path other=Files.createDirectory(fixture.resolve("other"));Files.createSymbolicLink(other.resolve("hall.json"),real.resolve("hall.json"));
        assertThrows(IOException.class,()->new ProjectStore(other));assertArrayEquals(before,Files.readAllBytes(real.resolve("hall.json")));
    }
    @Test void malformedLedgerIsPreservedAndUnknownReadsFailClosed() throws Exception {
        Path root=scratch();ProjectStore store=new ProjectStore(root);
        assertThrows(IOException.class,()->store.read("missing"));assertThrows(IOException.class,()->store.read("../escape"));
        Path malformed=Files.writeString(root.resolve("hall.json"),"{\"definition\":null}");
        assertThrows(IOException.class,()->new ProjectStore(root));assertEquals("{\"definition\":null}",Files.readString(malformed));
    }
}
