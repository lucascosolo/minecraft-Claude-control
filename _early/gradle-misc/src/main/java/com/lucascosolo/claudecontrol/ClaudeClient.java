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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** One agent turn: user message, then the manual tool-use loop. Runs on the agent worker thread only. */
public final class ClaudeClient implements AgentService.TurnRunner {
    public record Config(String apiKey, String model, String effort, int maxToolIterations,
                         int maxCommandsPerCall, String systemPrompt) {
        @Override
        public String toString() {
            return "Config[apiKey=<redacted>, model=" + model + ", effort=" + effort + ", maxToolIterations=" + maxToolIterations
                    + ", maxCommandsPerCall=" + maxCommandsPerCall + "]";
        }
    }

    static final String BETAS = "compact-2026-01-12,server-side-fallback-2026-07-01,thinking-binding-controls-2026-08-01";
    static final long MAX_TOKENS = 16000;
    static final List<ToolUnion> TOOLS = List.of(
            customTool("run_commands",
                    "Run one or more server console commands (op-level authority) in order, without a leading slash. "
                            + "Use this whenever acting on the world: give, tp, time, weather, effect, summon, fill, etc. "
                            + "Each command's output is returned; read it and adapt if a command failed or was denied. "
                            + "Set anchor {world, x, z} for anything that places, changes or tests blocks, so the batch runs in the region that owns "
                            + "that spot. Set near_player to an online player's name for commands that use entity selectors (@e, @a, @p, @n) or "
                            + "act on things around that player. anchor wins over near_player. Leave both null for global things "
                            + "(time, weather, gamerule, give or tell by name).",
                    obj("commands", obj("type", "array", "items", obj("type", "string"),
                            "description", "Commands to run, in order. Later commands still run if an earlier one fails."),
                            "near_player", obj("type", List.of("string", "null"),
                                    "description", "Online player whose region the whole batch runs in, or null."),
                            "anchor", obj("anyOf", List.of(obj("type", "object",
                                            "properties", obj("world", obj("type", "string"), "x", obj("type", "integer"), "z", obj("type", "integer")),
                                            "required", List.of("world", "x", "z"), "additionalProperties", false), obj("type", "null")),
                                    "description", "Block column whose region runs the whole batch, or null."))),
            customTool("survey_area",
                    "Look at the terrain before building or placing anything. Returns the ground height and surface material of every column "
                            + "in a square around (x, z), mobs inside it, and up to 3 flat sites of the requested footprint, nearest the centre first.",
                    obj("world", obj("type", "string", "description", "World name."),
                            "x", obj("type", "integer", "description", "Centre block x."),
                            "z", obj("type", "integer", "description", "Centre block z."),
                            "radius", obj("type", "integer", "description", "Half-width of the square, 1 to 12."),
                            "footprint", obj("type", "integer", "description", "Side of the flat site wanted, 3 to 15."))),
            customTool("teleport_player",
                    "Teleport an online player to the centre of an exact block.",
                    obj("player", obj("type", "string", "description", "Exact player name."),
                            "world", obj("type", "string", "description", "World name."),
                            "x", obj("type", "integer", "description", "Block x."),
                            "y", obj("type", "integer", "description", "Block y (feet)."),
                            "z", obj("type", "integer", "description", "Block z."))),
            customTool("send_chat",
                    "Say something in the game chat. This is the only way players see your words; your plain reply text is never shown. "
                            + "Use it when a player addressed you or should be told something.",
                    obj("message", obj("type", "string", "description", "The chat message."),
                            "player", obj("anyOf", List.of(obj("type", "string"), obj("type", "null")),
                                    "description", "Online player to whisper to, or null to broadcast to everyone."))),
            customTool("get_player_details",
                    "Get full details for one online player: position, world, gamemode, health, food, xp level, held item, "
                            + "inventory summary and active effects. Call it before acting on a player when the snapshot is not enough.",
                    obj("player", obj("type", "string", "description", "Exact player name."))),
            ToolUnion.ofMemoryTool20250818(MemoryTool20250818.builder().build()));

    private final Logger log;
    private final ConversationStore store;
    private final MemoryStore memory;
    private final CommandRunner commands;
    private final GameTools game;
    private final WorldSnapshot snapshot;
    private volatile Config config;
    private volatile boolean stopping;
    private AnthropicClient client;
    private String clientKey;
    private List<MessageParam> history;

    public ClaudeClient(Config config, Logger log, ConversationStore store, MemoryStore memory,
                        CommandRunner commands, GameTools game, WorldSnapshot snapshot) {
        this.config = config;
        this.log = log;
        this.store = store;
        this.memory = memory;
        this.commands = commands;
        this.game = game;
        this.snapshot = snapshot;
        this.history = trimToLatestCompaction(new ArrayList<>(store.load()));
    }

    public void reconfigure(Config config) { this.config = config; }

    public String model() { return config.model(); }

    @Override
    public void reset() {
        history = new ArrayList<>();
        store.clear();
    }

    @Override
    public void stop() { stopping = true; }

    public void close() {
        if (client != null) client.close();
    }

    @Override
    public void run(List<Trigger> batch) throws Exception {
        Config cfg = config;
        AnthropicClient api = client(cfg.apiKey());
        String actors = batch.stream().map(t -> t.actor() == null ? t.source() : t.actor()).distinct().collect(Collectors.joining(","));
        int mark = history.size();
        try {
            history.add(userText("<world>\n" + snapshot.capture() + "</world>\n<events>\n" + TriggerBatch.render(batch) + "</events>"));
            for (int request = 1; ; request++) {
                Message response = api.messages().create(params(cfg));
                StopReason stop = response.stopReason().orElse(StopReason.END_TURN);
                if (stop.equals(StopReason.REFUSAL)) {
                    String category = response.stopDetails().flatMap(d -> d.category()).map(Object::toString).orElse("unspecified");
                    throw new IllegalStateException("Claude refused the turn (category " + category + "); turn rolled back.");
                }
                if (stop.equals(StopReason.MAX_TOKENS)) throw new IllegalStateException("Response hit max_tokens; turn rolled back.");
                history.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(response.content().stream().map(ClaudeClient::toParam).toList()).build());
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
                for (ToolUseBlock use : uses) results.add(execute ? runTool(use, cfg, actors)
                        : result(use.id(), "Not executed: the tool iteration limit for this turn was reached.", true));
                history.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
                if (!execute) {
                    log.warning("Turn stopped at max-tool-iterations (" + cfg.maxToolIterations() + ").");
                    break;
                }
            }
            history = trimToLatestCompaction(history);
            if (!stopping) store.save(history);
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
                .tools(TOOLS)
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

    private ContentBlockParam runTool(ToolUseBlock use, Config cfg, String actors) throws InterruptedException {
        JsonNode in = use._input().convert(JsonNode.class);
        try {
            return switch (use.name()) {
                case "run_commands" -> {
                    JsonNode list = in.path("commands");
                    if (!list.isArray() || list.isEmpty() || list.size() > cfg.maxCommandsPerCall()) {
                        yield result(use.id(), "commands must hold 1.." + cfg.maxCommandsPerCall() + " commands.", true);
                    }
                    List<CommandResult> out = new ArrayList<>();
                    String near = in.path("near_player").isTextual() ? in.path("near_player").asText() : null;
                    JsonNode a = in.path("anchor");
                    CommandRunner.Anchor anchor = a.isObject() ? new CommandRunner.Anchor(a.path("world").asText(), a.path("x").asInt(), a.path("z").asInt()) : null;
                    for (JsonNode c : list) out.add(commands.run(c.asText(), actors, near, anchor));
                    yield result(use.id(), ToolJson.commandResults(out), false);
                }
                case "survey_area" -> result(use.id(), game.surveyArea(in.path("world").asText(), in.path("x").asInt(), in.path("z").asInt(),
                        in.path("radius").asInt(), in.path("footprint").asInt()), false);
                case "teleport_player" -> result(use.id(), game.teleportPlayer(in.path("player").asText(), in.path("world").asText(),
                        in.path("x").asInt(), in.path("y").asInt(), in.path("z").asInt()), false);
                case "send_chat" -> result(use.id(), game.sendChat(in.path("message").asText(),
                        in.path("player").isTextual() ? in.path("player").asText() : null), false);
                case "get_player_details" -> result(use.id(), game.playerDetails(in.path("player").asText()), false);
                case "memory" -> result(use.id(), memory(in), false);
                default -> result(use.id(), "Unknown tool " + use.name(), true);
            };
        } catch (GameTools.ToolError | MemoryStore.MemoryException e) {
            return result(use.id(), e.getMessage(), true);
        } catch (InterruptedException e) {
            throw e;
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
        if (client == null || !key.equals(clientKey)) {
            if (client != null) client.close();
            client = AnthropicOkHttpClient.builder().apiKey(key).build();
            clientKey = key;
        }
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

    private static MessageParam userText(String text) {
        return MessageParam.builder().role(MessageParam.Role.USER).content(text).build();
    }

    private static ContentBlockParam result(String id, String content, boolean error) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(id).content(content).isError(error).build());
    }

    /** Insertion-ordered map, so the tool schemas serialise byte-identically on every run (prompt cache, thinking binding). */
    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static ToolUnion customTool(String name, String description, Map<String, Object> properties) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return ToolUnion.ofTool(Tool.builder()
                .name(name)
                .description(description)
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(List.copyOf(properties.keySet()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build());
    }
}
