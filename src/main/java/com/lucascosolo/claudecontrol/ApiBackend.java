package com.lucascosolo.claudecontrol;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MemoryTool20250818;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** The Anthropic API backend: one turn is the user message, then the manual tool-use loop. Runs on the agent worker thread only. */
public final class ApiBackend implements AgentBackend {
    public record Config(String apiKey, String model, String effort, int maxToolIterations, String systemPrompt) {
        @Override
        public String toString() {
            return "Config[apiKey=<redacted>, model=" + model + ", effort=" + effort + ", maxToolIterations=" + maxToolIterations + "]";
        }
    }

    static final String BETAS = "compact-2026-01-12,server-side-fallback-2026-07-01,thinking-binding-controls-2026-08-01";
    static final long MAX_TOKENS = 16000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Config config;
    private final Logger log;
    private final ConversationStore store;
    private final MemoryStore memory;
    private final ToolDispatcher dispatcher;
    private final List<ToolUnion> tools;
    private volatile boolean stopping;
    private AnthropicClient client;
    /** Loaded on the first turn, so a backend built by a reload sees what its predecessor saved. */
    private List<MessageParam> history;

    public ApiBackend(Config config, Logger log, ConversationStore store, MemoryStore memory, ToolDispatcher dispatcher, ToolCatalog catalog) {
        this.config = config;
        this.log = log;
        this.store = store;
        this.memory = memory;
        this.dispatcher = dispatcher;
        this.tools = apiTools(catalog);
    }

    /** The catalog's tools as strict custom tools, plus the Anthropic memory tool, which only this backend has. */
    static List<ToolUnion> apiTools(ToolCatalog catalog) {
        List<ToolUnion> out = new ArrayList<>();
        for (ToolCatalog.Tool t : catalog.tools()) {
            Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
            t.inputSchema().path("properties").properties()
                    .forEach(e -> props.putAdditionalProperty(e.getKey(), JsonValue.from(MAPPER.convertValue(e.getValue(), Object.class))));
            List<String> required = new ArrayList<>();
            t.inputSchema().path("required").forEach(r -> required.add(r.asText()));
            out.add(ToolUnion.ofTool(Tool.builder()
                    .name(t.name())
                    .description(t.description())
                    .strict(required.size() == t.inputSchema().path("properties").size())
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(props.build())
                            .required(required)
                            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                            .build())
                    .build()));
        }
        out.add(ToolUnion.ofMemoryTool20250818(MemoryTool20250818.builder().build()));
        return List.copyOf(out);
    }

    @Override
    public void reset() {
        history = new ArrayList<>();
        store.clear();
    }

    @Override
    public void stop() { stopping = true; }

    @Override
    public synchronized void close() {
        if (client != null) client.close();
    }

    @Override
    public String describe() { return "api (model " + config.model() + ")"; }

    @Override
    public void runTurn(String userTurn, String actors) throws Exception {
        Config cfg = config;
        AnthropicClient api = client(cfg.apiKey());
        if (history == null) history = trimToLatestCompaction(new ArrayList<>(store.load()));
        long started = System.nanoTime();
        long in = 0, out = 0;
        int mark = history.size();
        try {
            history.add(MessageParam.builder().role(MessageParam.Role.USER).content(userTurn).build());
            for (int request = 1; ; request++) {
                Message response = api.messages().create(params(cfg));
                in += response.usage().inputTokens();
                out += response.usage().outputTokens();
                StopReason stop = response.stopReason().orElse(StopReason.END_TURN);
                if (stop.equals(StopReason.REFUSAL)) {
                    String category = response.stopDetails().flatMap(d -> d.category()).map(Object::toString).orElse("unspecified");
                    throw new IllegalStateException("Claude refused the turn (category " + category + "); turn rolled back.");
                }
                if (stop.equals(StopReason.MAX_TOKENS)) throw new IllegalStateException("Response hit max_tokens; turn rolled back.");
                history.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(response.content().stream().map(ApiBackend::toParam).toList()).build());
                if (stop.equals(StopReason.PAUSE_TURN)) {
                    if (request > cfg.maxToolIterations()) {
                        throw new IllegalStateException("Turn paused past max-tool-iterations (" + cfg.maxToolIterations() + "); turn rolled back.");
                    }
                    continue;
                }
                List<ToolUseBlock> uses = response.content().stream().flatMap(b -> b.toolUse().stream()).toList();
                if (!stop.equals(StopReason.TOOL_USE) || uses.isEmpty()) {
                    response.content().forEach(b -> b.text().ifPresent(t -> log.fine("Claude: " + t.text())));
                    break;
                }
                boolean execute = request <= cfg.maxToolIterations();
                List<ContentBlockParam> results = new ArrayList<>();
                for (ToolUseBlock use : uses) results.add(execute ? runTool(use, actors)
                        : result(use.id(), "Not executed: the tool iteration limit for this turn was reached.", true));
                history.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
                if (!execute) {
                    log.warning("Turn stopped at max-tool-iterations (" + cfg.maxToolIterations() + ").");
                    break;
                }
            }
            history = trimToLatestCompaction(history);
            if (!stopping) store.save(history);
            log.info("Turn served by api in " + (System.nanoTime() - started) / 1_000_000 + " ms, " + in + " input / " + out + " output tokens.");
        } catch (Exception | Error e) {
            history.subList(Math.min(mark, history.size()), history.size()).clear();
            throw e;
        }
    }

    MessageCreateParams params(Config cfg) {
        return MessageCreateParams.builder()
                .model(cfg.model())
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(cfg.systemPrompt()).build()))
                .tools(tools)
                .messages(history)
                .thinking(ThinkingConfigAdaptive.builder()
                        .putAdditionalProperty("block_binding", JsonValue.from(obj("prefix_mismatch_behavior", "drop_block")))
                        .build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(cfg.effort())).build())
                .cacheControl(CacheControlEphemeral.builder().build())
                .putAdditionalBodyProperty("context_management", JsonValue.from(obj("edits", List.of(obj("type", "compact_20260112")))))
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .putAdditionalHeader("anthropic-beta", BETAS)
                .build();
    }

    private ContentBlockParam runTool(ToolUseBlock use, String actors) throws InterruptedException {
        JsonNode in = use._input().convert(JsonNode.class);
        if (!use.name().equals("memory")) {
            ToolResult r = dispatcher.call(use.name(), in, actors);
            return result(use.id(), r.text(), r.isError());
        }
        try {
            return result(use.id(), memory(in), false);
        } catch (MemoryStore.MemoryException e) {
            return result(use.id(), e.getMessage(), true);
        } catch (Exception e) {
            return result(use.id(), "Tool failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    private String memory(JsonNode in) {
        String path = in.path("path").asText(null);
        return switch (in.path("command").asText()) {
            case "view" -> {
                JsonNode r = in.path("view_range");
                yield r.isArray() && r.size() == 2 ? memory.view(path, r.get(0).asInt(), r.get(1).asInt()) : memory.view(path);
            }
            case "create" -> memory.create(path, in.path("file_text").asText(""));
            case "str_replace" -> memory.strReplace(path, in.path("old_str").asText(""), in.path("new_str").asText(""));
            case "insert" -> memory.insert(path, in.path("insert_line").asInt(-1),
                    in.has("insert_text") ? in.path("insert_text").asText() : in.path("new_str").asText(""));
            case "delete" -> memory.delete(path);
            case "rename" -> memory.rename(in.path("old_path").asText(null), in.path("new_path").asText(null));
            default -> throw new MemoryStore.MemoryException("Unknown memory command: " + in.path("command").asText());
        };
    }

    private synchronized AnthropicClient client(String key) {
        if (client == null) client = AnthropicOkHttpClient.builder().apiKey(key).build();
        return client;
    }

    /** Known blocks via the SDK's own mapping; unknown ones (compaction, fallback) are passed back verbatim. */
    static ContentBlockParam toParam(ContentBlock block) {
        if (block.isValid()) return block.toParam();
        return ObjectMappers.jsonMapper().convertValue(block._json().orElseThrow(), ContentBlockParam.class);
    }

    /** Messages before the latest compaction block are ignored by the API; drop them as the compaction docs allow. */
    static List<MessageParam> trimToLatestCompaction(List<MessageParam> messages) {
        for (int i = messages.size() - 1; i > 0; i--) {
            if (hasCompaction(messages.get(i))) return new ArrayList<>(messages.subList(i, messages.size()));
        }
        return messages;
    }

    private static boolean hasCompaction(MessageParam m) {
        return m.content().blockParams().stream().flatMap(List::stream).anyMatch(b -> b._json()
                .map(j -> "compaction".equals(j.convert(JsonNode.class).path("type").asText()))
                .orElse(false));
    }

    private static ContentBlockParam result(String id, String content, boolean error) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(id).content(content).isError(error).build());
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
