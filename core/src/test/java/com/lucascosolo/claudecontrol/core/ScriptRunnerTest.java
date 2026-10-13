package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ScriptRunnerTest {
    private JsonObject event() { return JsonParser.parseString("{\"type\":\"interact\",\"player_uuid\":\"00000000-0000-0000-0000-000000000001\",\"player_name\":\"Alice\"}").getAsJsonObject(); }

    @Test void namedCallbackReceivesPlainParamsAndPreservesExplicitStateAcrossCalls() {
        String script = """
            function found(event,state,game,params) {
              state.count = (state.count || 0) + 1;
              state.last = event.player_name;
              game.emit({type:'chat',args:{message:params.message}});
            }
            function onEvent(event,state,game) { state.events = (state.events || 0) + 1; }
            """;
        JsonObject state = new JsonObject(); state.addProperty("chapter", "harbor"); JsonObject before = state.deepCopy();
        JsonObject params = new JsonObject(); params.addProperty("message", "A clue appeared");
        try (ScriptRunner runner = new ScriptRunner()) {
            var first = runner.run(script, "found", event(), state, params);
            assertEquals(before, state); assertEquals(1, first.state().get("count").getAsInt());
            assertEquals("Alice", first.state().get("last").getAsString());
            assertEquals("harbor", first.state().get("chapter").getAsString());
            assertEquals(1, first.actions().size());
            assertEquals("chat", first.actions().get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("A clue appeared", first.actions().get(0).getAsJsonObject().getAsJsonObject("args").get("message").getAsString());
            var second = runner.run(script, "found", event(), first.state(), params);
            assertEquals(2, second.state().get("count").getAsInt());
            var generic = runner.run(script, "onEvent", event(), second.state(), new JsonObject());
            assertEquals(2, generic.state().get("count").getAsInt()); assertEquals(1, generic.state().get("events").getAsInt());
        }
    }

    @Test void onlyExplicitStatePersistsBetweenInvocations() {
        String script = "var hidden = 0; function found(event,state,game,params) { hidden++; state.hidden = hidden; }";
        try (ScriptRunner runner = new ScriptRunner()) {
            var first = runner.run(script, "found", event(), new JsonObject(), new JsonObject());
            var second = runner.run(script, "found", event(), first.state(), new JsonObject());
            assertEquals(1, first.state().get("hidden").getAsInt()); assertEquals(1, second.state().get("hidden").getAsInt());
        }
    }

    @Test void javaFileNetworkProcessAndConsoleGlobalsAreUnavailable() {
        String script = """
            function found(event,state,game,params) {
              state.types = [typeof java,typeof Packages,typeof Java,typeof importClass,
                typeof require,typeof process,typeof fetch,typeof XMLHttpRequest,
                typeof console,typeof print,typeof readFile,typeof load,typeof quit];
            }
            """;
        try (ScriptRunner runner = new ScriptRunner()) {
            var result = runner.run(script, "found", event(), new JsonObject(), new JsonObject());
            for (JsonElement type : result.state().getAsJsonArray("types")) assertEquals("undefined", type.getAsString());
            assertTrue(result.actions().isEmpty());
        }
    }

    @Test void hostReflectionAndConsoleEmissionsFailClosed() {
        for (String body : new String[]{
            "java.lang.System.getProperty('user.dir');", "Packages.java.lang.Runtime.getRuntime();",
            "event.getClass();", "game.getClass();", "game.console({command:'say unsafe'});",
            "game.emit({type:'chat',args:{message:'valid'}}); game.command({command:'say unsafe'});"}) {
            try (ScriptRunner runner = new ScriptRunner()) {
                assertThrows(RuntimeException.class, () -> runner.run("function found(event,state,game,params) {" + body + "}", "found", event(), new JsonObject(), new JsonObject()), body);
            }
        }
    }

    @Test void missingCallbacksAreRejectedDuringDefinitionValidation() {
        try (ScriptRunner runner = new ScriptRunner()) {
            JsonObject missing = ExperienceSpecTest.definition(); missing.addProperty("script", "function unrelated() {}");
            assertThrows(RuntimeException.class, () -> runner.validate(ExperienceSpec.parse(missing)));
            JsonObject generic = ExperienceSpecTest.definition(); generic.add("script_events", JsonParser.parseString("[\"join\"]"));
            assertThrows(RuntimeException.class, () -> runner.validate(ExperienceSpec.parse(generic)));
            assertDoesNotThrow(() -> runner.validate(ExperienceSpec.parse(ExperienceSpecTest.definition())));
        }
    }

    @Test void loopsAreBoundedAndWorkerCanServeNextRequest() {
        try (ScriptRunner runner = new ScriptRunner()) {
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> assertThrows(RuntimeException.class,
                () -> runner.run("function found(event,state,game,params) { while(true) {} }", "found", event(), new JsonObject(), new JsonObject())));
            var recovered = runner.run("function found(event,state,game,params) { state.ok=true; }", "found", event(), new JsonObject(), new JsonObject());
            assertTrue(recovered.state().get("ok").getAsBoolean());
        }
    }

    @Test void emissionsGiftCountsAndReturnedStateAreBoundedAtomically() {
        for (String body : new String[]{
            "for(var i=0;i<17;i++) game.emit({type:'chat',args:{message:'hello'}});",
            "game.emit({type:'give_item',args:{material:'STONE',amount:17}});",
            "state.payload = new Array(40000).join('x');",
            "game.emit({type:'chat',args:{message:'ok'}}); state.self = state;",
            "game.emit({type:'chat',args:{message:new Array(40000).join('x')}});"}) {
            JsonObject state = new JsonObject(); state.addProperty("chapter", 1); JsonObject before = state.deepCopy();
            try (ScriptRunner runner = new ScriptRunner()) {
                assertThrows(RuntimeException.class, () -> runner.run("function found(event,state,game,params) {" + body + "}", "found", event(), state, new JsonObject()), body);
                assertEquals(before, state);
            }
        }
    }

    @Test void emissionAndGiftLimitBoundariesRemainUsable() {
        try (ScriptRunner runner = new ScriptRunner()) {
            var chats = runner.run("function found(event,state,game,params) { for(var i=0;i<16;i++) game.emit({type:'chat',args:{message:'hello'}}); }", "found", event(), new JsonObject(), new JsonObject());
            assertEquals(16, chats.actions().size());
            var gift = runner.run("function found(event,state,game,params) { game.emit({type:'give_item',args:{material:'STONE',amount:16}}); }", "found", event(), new JsonObject(), new JsonObject());
            assertEquals(16, gift.actions().get(0).getAsJsonObject().getAsJsonObject("args").get("amount").getAsInt());
        }
    }

    @Test void numericBuildWorldRejectsWholeBatchWithoutReturningEarlierActions() {
        String script = """
            function found(event,state,game,params) {
              state.changed = true;
              game.emit({type:'chat',args:{message:'Earlier valid action'}});
              game.emit({type:'build',args:{world:123,x:10,y:80,z:10,
                width:1,depth:1,height:1,replace_existing:false,
                operations:[{x:0,y:0,z:0,material:'STONE'}]}});
            }
            """;
        JsonObject state = new JsonObject(); state.addProperty("chapter", 1);
        JsonObject before = state.deepCopy();
        try (ScriptRunner runner = new ScriptRunner()) {
            assertThrows(IllegalArgumentException.class,
                () -> runner.run(script, "found", event(), state, new JsonObject()));
            assertEquals(before, state);
            var valid = runner.run(script.replace("world:123", "world:'world'"), "found", event(), state, new JsonObject());
            assertEquals(2, valid.actions().size());
            assertEquals("build", valid.actions().get(1).getAsJsonObject().get("type").getAsString());
        }
    }

    @Test void hugeNativeStringAllocationIsContainedInChildAndNextCallbackRecovers() {
        // The large allocation exists only in the child script; JUnit holds tiny JSON fixtures.
        String script = "function found(event,state,game,params) { state.payload='x'.repeat(1000000000); }";
        JsonObject state = new JsonObject(); state.addProperty("chapter", 1);
        JsonObject before = state.deepCopy();
        try (ScriptRunner runner = new ScriptRunner()) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                assertThrows(RuntimeException.class,
                    () -> runner.run(script, "found", event(), state, new JsonObject()));
                assertEquals(before, state);
                var recovered = runner.run("function found(event,state,game,params) { state.ok=true; }", "found", event(), state, new JsonObject());
                assertTrue(recovered.state().get("ok").getAsBoolean());
                assertTrue(recovered.actions().isEmpty());
            });
        }
    }
}
