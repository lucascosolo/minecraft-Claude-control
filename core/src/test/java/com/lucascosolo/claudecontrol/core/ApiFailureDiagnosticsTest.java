package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.net.http.HttpTimeoutException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ApiFailureDiagnosticsTest {
    private static final String KEY="sk-ant-diagnostics-private-canary";
    private static final String PRIVATE="transport-private-payload-canary";
    private final Turn player=new Turn(UUID.randomUUID(),"TownOwner",true,"Original mansion-request-canary",false);
    private Path conversation() throws Exception {
        Path base=Path.of("build","test-scratch").toAbsolutePath();Files.createDirectories(base);
        Path root=Files.createTempDirectory(base,"api-failure-");
        Files.writeString(root.resolve("canary.txt"),"preserve fixture");
        return root.resolve("conversation.json");
    }
    private Settings settings(Map<String,Object> extra) {
        Map<String,Object> config=new HashMap<>(extra);config.put("api-key",KEY);return Settings.from(config,Map.of());
    }
    private JsonObject answer(String text) {
        JsonObject response=new JsonObject();response.addProperty("stop_reason","end_turn");
        JsonObject block=new JsonObject();block.addProperty("type","text");block.addProperty("text",text);
        JsonArray content=new JsonArray();content.add(block);response.add("content",content);return response;
    }
    private JsonObject toolRequest(String id) {
        JsonObject response=answer("The next build stage is ready.");response.addProperty("stop_reason","tool_use");
        JsonObject block=new JsonObject();block.addProperty("type","tool_use");block.addProperty("id",id);
        block.addProperty("name","build");block.add("input",JsonParser.parseString("{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"width\":1,\"height\":1,\"depth\":1,\"operations\":[{\"x\":0,\"y\":0,\"z\":0,\"material\":\"STONE\"}]}"));
        response.getAsJsonArray("content").add(block);return response;
    }
    private List<String> progress(Path saved) throws Exception {
        try(var files=Files.list(saved.getParent())) {
            List<String> result=new ArrayList<>();
            for(Path file:files.filter(p->p.getFileName().toString().startsWith(".conversation-action-record-")).toList())result.add(Files.readString(file));
            return result;
        }
    }
    private void safe(ApiAgent.TurnFailure error) {
        assertFalse(error.toString().contains(KEY));assertFalse(error.toString().contains(PRIVATE));
        if(error.getCause()!=null){assertFalse(error.getCause().toString().contains(KEY));assertFalse(error.getCause().toString().contains(PRIVATE));}
    }
    private void retainedOriginalRequest(Path saved) throws Exception {
        List<String> records=progress(saved);assertFalse(records.isEmpty(),"Failed original requests need durable progress even before tools");
        assertTrue(records.stream().anyMatch(record->record.contains(player.text())));
        assertTrue(records.stream().noneMatch(record->record.contains(KEY)||record.contains(PRIVATE)));
        assertEquals("preserve fixture",Files.readString(saved.getParent().resolve("canary.txt")));
    }
    @Test void maxTokensWithCompleteToolInputRefusesAllToolsAndReportsOutputLimit() throws Exception {
        Path saved=conversation();AtomicInteger actions=new AtomicInteger();
        JsonObject truncated=toolRequest("truncated-build");truncated.addProperty("stop_reason","max_tokens");
        try(ApiAgent agent=new ApiAgent(settings(Map.of()),"rules",new JsonArray(),request->truncated.deepCopy(),saved)) {
            ApiAgent.TurnFailure failure=assertThrows(ApiAgent.TurnFailure.class,()->agent.runTurn(player,"loaded world",(name,input,turn)->{actions.incrementAndGet();return new ToolReply("must not execute",false);}));
            assertEquals(ApiAgent.FailureReason.OUTPUT_LIMIT,failure.reason());safe(failure);
            assertEquals(0,actions.get());assertEquals(0,agent.history().size());assertFalse(Files.exists(saved));
            retainedOriginalRequest(saved);
        }
    }
    @Test void maxTokensWithPlainTextIsNotCommittedAsACompleteAnswer() throws Exception {
        Path saved=conversation();JsonObject truncated=answer("Half of the mansion design");truncated.addProperty("stop_reason","max_tokens");
        try(ApiAgent agent=new ApiAgent(settings(Map.of()),"rules",new JsonArray(),request->truncated,saved)) {
            ApiAgent.TurnFailure failure=assertThrows(ApiAgent.TurnFailure.class,()->agent.runTurn(player,"world",(name,input,turn)->{fail("Truncation must not run tools");return null;}));
            assertEquals(ApiAgent.FailureReason.OUTPUT_LIMIT,failure.reason());assertEquals(0,agent.history().size());
            retainedOriginalRequest(saved);
        }
    }
    @Test void injectedHttpTimeoutHasSafeSpecificReasonAndRetainsFirstRequest() throws Exception {
        Path saved=conversation();AtomicInteger actions=new AtomicInteger();
        try(ApiAgent agent=new ApiAgent(settings(Map.of()),"rules",new JsonArray(),request->{throw new HttpTimeoutException(PRIVATE+" "+KEY);},saved)) {
            ApiAgent.TurnFailure failure=assertThrows(ApiAgent.TurnFailure.class,()->agent.runTurn(player,"world",(name,input,turn)->{actions.incrementAndGet();return new ToolReply("unused",false);}));
            assertEquals(ApiAgent.FailureReason.TIMEOUT,failure.reason());safe(failure);assertEquals(0,actions.get());
            assertEquals(0,agent.history().size());assertFalse(Files.exists(saved));retainedOriginalRequest(saved);
        }
    }
    @Test void providerAuthenticationFailurePreservesHttpCodeWithoutCredentials() throws Exception {
        Path saved=conversation();
        try(ApiAgent agent=new ApiAgent(settings(Map.of()),"rules",new JsonArray(),request->{throw new ApiAgent.ApiFailure(401);},saved)) {
            ApiAgent.TurnFailure failure=assertThrows(ApiAgent.TurnFailure.class,()->agent.runTurn(player,"world",(name,input,turn)->{fail("Provider failure cannot execute tools");return null;}));
            assertEquals(ApiAgent.FailureReason.PROVIDER,failure.reason());assertTrue(failure.getMessage().contains("HTTP 401"));safe(failure);
            retainedOriginalRequest(saved);assertFalse(Files.exists(saved));
        }
    }
    @Test void unknownTransportFailureDoesNotExposePrivateExceptionDetails() throws Exception {
        Path saved=conversation();
        try(ApiAgent agent=new ApiAgent(settings(Map.of()),"rules",new JsonArray(),request->{throw new Exception(PRIVATE+" "+KEY);},saved)) {
            ApiAgent.TurnFailure failure=assertThrows(ApiAgent.TurnFailure.class,()->agent.runTurn(player,"world",(name,input,turn)->{fail("Failed transport cannot execute tools");return null;}));
            assertEquals(ApiAgent.FailureReason.OTHER,failure.reason());safe(failure);retainedOriginalRequest(saved);
        }
    }
    @Test void malformedResponseReportsInvalidResponseAndRetainsOriginalRequest() throws Exception {
        Path saved=conversation();
        try(ApiAgent agent=new ApiAgent(settings(Map.of()),"rules",new JsonArray(),request->new JsonObject(),saved)) {
            ApiAgent.TurnFailure failure=assertThrows(ApiAgent.TurnFailure.class,()->agent.runTurn(player,"world",(name,input,turn)->{fail("Malformed response cannot execute tools");return null;}));
            assertEquals(ApiAgent.FailureReason.INVALID_RESPONSE,failure.reason());safe(failure);retainedOriginalRequest(saved);
        }
    }
    @Test void completedActionThenTruncationRetainsReceiptAndWarningWithoutRepeatingAction() throws Exception {
        Path saved=conversation();AtomicInteger requests=new AtomicInteger();AtomicInteger actions=new AtomicInteger();
        JsonObject truncated=toolRequest("second-unexecuted-build");truncated.addProperty("stop_reason","max_tokens");
        try(ApiAgent agent=new ApiAgent(settings(Map.of()),"rules",new JsonArray(),request->switch(requests.getAndIncrement()) {
            case 0->answer("Existing completed planning turn.");case 1->toolRequest("first-completed-build");case 2->truncated;default->answer("Review recorded progress before further building.");
        },saved)) {
            agent.runTurn(player,"world",(name,input,turn)->{fail("Initial plan has no tools");return null;});
            JsonArray history=agent.history();String persisted=Files.readString(saved);
            ApiAgent.TurnFailure failure=assertThrows(ApiAgent.TurnFailure.class,()->agent.runTurn(player,"world",(name,input,turn)->{actions.incrementAndGet();return new ToolReply("Placed the first-stage foundation block successfully",false);}));
            assertEquals(ApiAgent.FailureReason.OUTPUT_LIMIT,failure.reason());safe(failure);assertEquals(1,actions.get());
            assertTrue(failure.getMessage().contains("World actions"));assertTrue(failure.getMessage().contains("remain"));
            assertEquals(history,agent.history());assertEquals(persisted,Files.readString(saved));
            List<String> records=progress(saved);
            assertTrue(records.stream().anyMatch(record->record.contains("first-completed-build")&&record.contains("tool_result")&&record.contains("Placed the first-stage foundation block successfully")));
            retainedOriginalRequest(saved);
            try(ApiAgent restored=new ApiAgent(settings(Map.of()),"rules",new JsonArray(),request->answer("Review recorded progress."),saved)) {
                assertEquals(history,restored.history());
                restored.runTurn(player,"world",(name,input,turn)->{actions.incrementAndGet();return new ToolReply("unexpected replay",false);});
                assertEquals(1,actions.get(),"Restoring history must never replay saved world effects");
            }
        }
    }
    @Test void toolRoundLimitHasSpecificReasonAndRetainsOnlyExecutedReceipts() throws Exception {
        Path saved=conversation();AtomicInteger requests=new AtomicInteger();AtomicInteger actions=new AtomicInteger();
        try(ApiAgent agent=new ApiAgent(settings(Map.of("max-tool-iterations",1)),"rules",new JsonArray(),request->toolRequest("build-"+requests.incrementAndGet()),saved)) {
            ApiAgent.TurnFailure failure=assertThrows(ApiAgent.TurnFailure.class,()->agent.runTurn(player,"world",(name,input,turn)->{actions.incrementAndGet();return new ToolReply("First build stage actually completed",false);}));
            assertEquals(ApiAgent.FailureReason.TOOL_LIMIT,failure.reason());assertEquals(1,actions.get());assertEquals(2,requests.get());safe(failure);
            assertTrue(progress(saved).stream().anyMatch(record->record.contains("First build stage actually completed")));retainedOriginalRequest(saved);
        }
    }
}
