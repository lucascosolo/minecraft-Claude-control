package com.lucascosolo.claudecontrol.core;

import com.google.gson.*;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Serial Messages API conversation; durable state is committed only after a complete turn. */
public final class ApiAgent implements AutoCloseable {
    private static final int TURN_BYTES=512*1024, REQUEST_BYTES=1024*1024, RESPONSE_BYTES=256*1024, RESULT_BYTES=8192;
    @FunctionalInterface public interface Transport extends AutoCloseable { JsonObject send(JsonObject request) throws Exception; @Override default void close() {} }
    public record Usage(long inputTokens,long outputTokens,long cacheReadInputTokens,long cacheCreationInputTokens) {}
    private volatile Usage lastUsage=new Usage(0,0,0,0);
    public Usage lastUsage(){return lastUsage;}
    public static final class ApiFailure extends Exception {
        private final int status;
        public ApiFailure(int status) { super("Claude API request failed (HTTP " + status + ")"); this.status=status; }
        public int status(){return status;}
    }
    public enum FailureReason {OUTPUT_LIMIT,TIMEOUT,PROVIDER,INVALID_RESPONSE,TOOL_LIMIT,OTHER}
    public static final class TurnFailure extends IllegalStateException {
        private final FailureReason reason;
        TurnFailure(FailureReason reason,int status,boolean executed) {
            super("Claude turn failed"+(status>0?" (HTTP "+status+")":"")+": "+switch(reason) {
                case OUTPUT_LIMIT -> "response reached the output limit; use compact build shapes and smaller saved stages";
                case TIMEOUT -> "provider response timed out; inspect saved project progress before retrying";
                case PROVIDER -> "provider rejected the request";
                case INVALID_RESPONSE -> "provider response was incomplete or invalid";
                case TOOL_LIMIT -> "tool round limit reached; continue from saved project progress";
                case OTHER -> "request could not complete";
            }+". Conversation was not committed; interrupted request retained."+(executed?" World actions already performed remain in the world.":" No world actions were performed."));
            this.reason=reason;
        }
        public FailureReason reason(){return reason;}
    }
    private final Settings settings;
    private final String prompt, filename;
    private final JsonArray tools;
    private final Transport transport;
    private final ConversationStore store;
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    private JsonArray turns=new JsonArray();
    public ApiAgent(Settings settings,String prompt,JsonArray tools,Transport transport,Path conversation) throws Exception {
        this.settings=Objects.requireNonNull(settings); this.prompt=Objects.requireNonNull(prompt); this.tools=tools.deepCopy(); this.transport=Objects.requireNonNull(transport);
        if(!settings.apiKey().isBlank() && (prompt.contains(settings.apiKey()) || tools.toString().contains(settings.apiKey()))) throw new IllegalArgumentException("Credentials must not appear in API instructions");
        if(prompt.length()>65536 || tools.size()>32 || tools.toString().length()>65536) throw new IllegalArgumentException("API instructions exceed limits");
        Path p=conversation.toAbsolutePath().normalize(); filename=p.getFileName().toString(); store=new ConversationStore(p.getParent());
        String saved=store.read(filename);
        if(!saved.isBlank()) {
            try { if(!settings.apiKey().isBlank() && saved.contains(settings.apiKey())) throw new IllegalArgumentException(); JsonArray loaded=JsonParser.parseString(saved).getAsJsonArray(); for(JsonElement t:loaded) validateCompleted(t.getAsJsonArray()); turns=trim(loaded); }
            catch(Exception e) { throw new IllegalArgumentException("Invalid saved conversation; preserve it for review"); }
        }
    }
    private static JsonObject message(String role,JsonElement content) { JsonObject m=new JsonObject(); m.addProperty("role",role); m.add("content",content); return m; }
    private static JsonArray flatten(JsonArray grouped) { JsonArray result=new JsonArray(); for(JsonElement group:grouped) for(JsonElement m:group.getAsJsonArray()) result.add(m.deepCopy()); return result; }
    public synchronized JsonArray history(){return flatten(turns);}
    public synchronized void reset() throws Exception { store.archive(filename); turns=new JsonArray(); }
    private static int bytes(JsonElement value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    private JsonArray trim(JsonArray source) { JsonArray result=source.deepCopy(); while(result.size()>settings.historyTurns()) result.remove(0); while(result.size()>1 && bytes(result)>ConversationStore.MAX_BYTES) result.remove(0); return result; }
    @Override public void close() { if(closed.compareAndSet(false,true)) transport.close(); }
    private JsonObject request(JsonArray messages) {
        JsonObject r=new JsonObject(); r.addProperty("model",settings.model()); r.addProperty("max_tokens",settings.maxTokens());
        JsonObject cache=new JsonObject(); cache.addProperty("type","ephemeral");
        JsonObject system=new JsonObject(); system.addProperty("type","text"); system.addProperty("text",prompt); system.add("cache_control",cache.deepCopy());
        JsonArray systemBlocks=new JsonArray(); systemBlocks.add(system); r.add("system",systemBlocks); r.add("messages",messages.deepCopy());
        // One static prefix boundary covers tools+system; automatic caching advances with completed conversation blocks.
        r.add("cache_control",cache);
        JsonObject thinking=new JsonObject(); thinking.addProperty("type","adaptive"); r.add("thinking",thinking);
        JsonObject output=new JsonObject(); output.addProperty("effort",settings.effort()); r.add("output_config",output); if(!tools.isEmpty()) r.add("tools",tools.deepCopy()); return r;
    }
    public synchronized String runTurn(Turn turn,String worldReport,ToolExecutor executor) throws Exception {
        if(settings.apiKey().isBlank()) throw new IllegalStateException("Claude API key is not configured");
        if(closed.get()) throw new IllegalStateException("Claude API agent is closed");
        lastUsage=new Usage(0,0,0,0);
        JsonArray current=new JsonArray(); current.add(message("user",new JsonPrimitive(safe(turn.envelope(worldReport)))));
        int rounds=0; boolean executed=false;
        try {
            while(true) {
                if(closed.get()) throw new IllegalStateException("Claude API agent is closed");
                JsonArray previous=turns.deepCopy(); JsonObject payload;
                while(true) { JsonArray messages=flatten(previous); for(JsonElement item:current) messages.add(item.deepCopy()); payload=request(messages); if(bytes(payload)<=REQUEST_BYTES) break; if(previous.isEmpty()) throw new IllegalStateException("API request exceeds byte budget"); previous.remove(0); }
                JsonObject response=transport.send(payload);
                if(response!=null && response.has("usage")) {
                    JsonObject u=response.getAsJsonObject("usage"); Usage old=lastUsage;
                    lastUsage=new Usage(old.inputTokens()+tokens(u,"input_tokens"),old.outputTokens()+tokens(u,"output_tokens"),old.cacheReadInputTokens()+tokens(u,"cache_read_input_tokens"),old.cacheCreationInputTokens()+tokens(u,"cache_creation_input_tokens"));
                }
                if(response!=null && response.has("stop_reason") && response.get("stop_reason").isJsonPrimitive()
                    && response.get("stop_reason").getAsString().equals("max_tokens"))throw new TurnFailure(FailureReason.OUTPUT_LIMIT,0,executed);
                if(response==null || !response.has("content") || !response.get("content").isJsonArray() || !response.has("stop_reason")) throw new IllegalArgumentException("Malformed API response");
                JsonArray content=response.getAsJsonArray("content"); if(content.isEmpty() || bytes(content)>RESPONSE_BYTES) throw new IllegalArgumentException("Invalid API response content");
                if(content.toString().contains(settings.apiKey())) throw new IllegalArgumentException("API response contains credentials");
                String stop=response.get("stop_reason").getAsString(); JsonArray results=new JsonArray(); StringBuilder text=new StringBuilder(); Set<String> ids=new HashSet<>();
                // Validate every block before any tool executes.
                for(JsonElement block:content) {
                    JsonObject b=block.getAsJsonObject(); String type=b.get("type").getAsString();
                    if(type.equals("tool_use")) { if(!stop.equals("tool_use") || !b.has("id") || !b.has("name") || !b.has("input") || !b.get("input").isJsonObject() || !b.get("id").getAsString().matches("[a-zA-Z0-9_-]{1,128}") || !b.get("name").getAsString().matches("[a-zA-Z0-9_-]{1,128}") || !ids.add(b.get("id").getAsString()) || ids.size()>16) throw new IllegalArgumentException("Malformed API tool request"); }
                    else if(type.equals("text")) { if(!b.has("text")) throw new IllegalArgumentException("Malformed API text"); text.append(b.get("text").getAsString()); }
                    else if(!Set.of("thinking","redacted_thinking").contains(type)) throw new IllegalArgumentException("Unsupported API content");
                }
                current.add(message("assistant",content.deepCopy()));
                if(bytes(current)>TURN_BYTES) throw new IllegalStateException("Claude turn exceeds transcript byte budget");
                if(ids.isEmpty()) {
                    if(!stop.equals("end_turn") || text.isEmpty()) throw new IllegalArgumentException("API did not complete its turn");
                    JsonArray next=turns.deepCopy(); next.add(current); next=trim(next); validateCompleted(current);
                    store.write(filename,next.toString()); turns=next; return safe(text.toString());
                }
                if(rounds++>=settings.maxToolIterations()) throw new IllegalStateException("Claude reached its tool round limit");
                // Reserve every result and a final answer before executing any additional world action.
                if(bytes(current)+(long)ids.size()*(RESULT_BYTES+512)+RESPONSE_BYTES>TURN_BYTES) throw new IllegalStateException("Claude turn cannot safely fit another tool round");
                JsonObject intent=new JsonObject(); intent.addProperty("state","pending_tool_actions"); intent.add("messages",current.deepCopy()); store.retainProgress(intent.toString());
                for(JsonElement block:content) {
                    JsonObject b=block.getAsJsonObject(); if(!b.get("type").getAsString().equals("tool_use")) continue;
                    ToolReply reply; try { executed=true; reply=executor.execute(b.get("name").getAsString(),b.getAsJsonObject("input").deepCopy(),turn); if(reply==null) throw new IllegalArgumentException(); }
                    catch(Exception e) { reply=new ToolReply("Tool failed; inspect local policy and try a smaller safe action.",true); }
                    JsonObject result=new JsonObject(); result.addProperty("type","tool_result"); result.addProperty("tool_use_id",b.get("id").getAsString()); result.addProperty("is_error",reply.error()); String value=safe(reply.text()); if(bytes(new JsonPrimitive(value))>RESULT_BYTES) value="Tool finished; detailed output exceeds the response budget. Do not repeat the action to recover its output."; result.addProperty("content",value); results.add(result);
                }
                current.add(message("user",results));
                JsonObject progress=new JsonObject(); progress.addProperty("state","tool_actions_recorded"); progress.add("messages",current.deepCopy()); store.retainProgress(progress.toString());
            }
        } catch(Exception failure) {
            try { JsonObject recovery=new JsonObject(); recovery.addProperty("state",executed?"interrupted_turn_world_actions_may_remain":"interrupted_request_before_world_actions"); recovery.add("messages",current.deepCopy()); store.retainProgress(recovery.toString()); } catch(Exception ignored) {}
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            FailureReason reason=failure instanceof TurnFailure typed?typed.reason():failure instanceof HttpTimeoutException?FailureReason.TIMEOUT:
                failure instanceof ApiFailure?FailureReason.PROVIDER:failure instanceof IllegalArgumentException || failure instanceof JsonParseException?FailureReason.INVALID_RESPONSE:
                "Claude reached its tool round limit".equals(failure.getMessage())?FailureReason.TOOL_LIMIT:FailureReason.OTHER;
            throw new TurnFailure(reason,failure instanceof ApiFailure api?api.status():0,executed);
        }
    }
    private String safe(String text) { return settings.apiKey().isEmpty()?text:text.replace(settings.apiKey(),"[redacted]"); }
    private static long tokens(JsonObject usage,String field) {
        if(!usage.has(field)) return 0; JsonElement value=usage.get(field);
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid API usage count");
        try { long count=value.getAsBigDecimal().longValueExact(); if(count<0 || count>1000000000L) throw new ArithmeticException(); return count; }
        catch(ArithmeticException invalid) { throw new IllegalArgumentException("Invalid API usage count"); }
    }
    private static void validateCompleted(JsonArray group) {
        if(group.size()<2 || group.size()>34 || !group.get(0).getAsJsonObject().get("role").getAsString().equals("user") || !group.get(group.size()-1).getAsJsonObject().get("role").getAsString().equals("assistant")) throw new IllegalArgumentException("Incomplete conversation turn");
        for(int i=0;i<group.size();i++) {
            JsonObject m=group.get(i).getAsJsonObject(); if(!m.has("content") || !m.get("role").getAsString().equals(i%2==0?"user":"assistant")) throw new IllegalArgumentException("Invalid conversation order");
            if(i==0) { if(!m.get("content").isJsonPrimitive() || !m.getAsJsonPrimitive("content").isString()) throw new IllegalArgumentException("Invalid trigger content"); continue; }
            JsonArray blocks=m.getAsJsonArray("content"); if(blocks.isEmpty()) throw new IllegalArgumentException("Empty conversation message");
            if(i%2==1) {
                Set<String> requests=new HashSet<>(); for(JsonElement item:blocks) { JsonObject b=item.getAsJsonObject(); if(b.get("type").getAsString().equals("tool_use") && !requests.add(b.get("id").getAsString())) throw new IllegalArgumentException("Duplicate tool identifier"); }
                if(i==group.size()-1 && !requests.isEmpty()) throw new IllegalArgumentException("Unresolved tool request");
                if(i<group.size()-1) { JsonArray replies=group.get(i+1).getAsJsonObject().getAsJsonArray("content"); Set<String> resolved=new HashSet<>(); for(JsonElement item:replies) { JsonObject b=item.getAsJsonObject(); if(!b.get("type").getAsString().equals("tool_result") || !resolved.add(b.get("tool_use_id").getAsString()) || !b.has("content") || !b.has("is_error")) throw new IllegalArgumentException("Invalid tool result"); } if(requests.isEmpty() || !requests.equals(resolved)) throw new IllegalArgumentException("Mismatched tool results"); }
            }
        }
    }
    public static Transport httpTransport(Settings settings) {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new Transport() {
          @Override public void close() { client.shutdownNow(); }
          @Override public JsonObject send(JsonObject payload) throws Exception {
            if(settings.apiKey().isBlank()) throw new IllegalStateException("Claude API key is not configured");
            if(bytes(payload)>REQUEST_BYTES) throw new IllegalStateException("API request exceeds byte budget");
            HttpRequest req=HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages")).timeout(Duration.ofSeconds(120)).header("x-api-key",settings.apiKey()).header("anthropic-version","2023-06-01").header("content-type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString(),StandardCharsets.UTF_8)).build();
            HttpResponse<java.io.InputStream> response=client.send(req,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()) { if(response.statusCode()!=200) throw new ApiFailure(response.statusCode()); byte[] bytes=body.readNBytes(1048577); if(bytes.length>1048576) throw new IllegalStateException("API response exceeds limits"); try { return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject(); } catch(Exception invalid) { throw new IllegalStateException("Malformed API response"); } }
          }
        };
    }
}
