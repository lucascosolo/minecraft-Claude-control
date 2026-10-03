package com.lucascosolo.claudecontrol;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClaudeControlPlugin extends JavaPlugin {
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");
    private AgentService agent;
    private ClaudeClient claude;
    private CommandRunner commands;
    private GameTools game;
    private volatile RandomEventTrigger events;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!Files.exists(systemPromptFile())) saveResource("system-prompt.md", false);
        FileConfiguration c;
        try {
            c = loadConfig();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load config.yml: " + e.getMessage(), e);
        }
        Path data = getDataFolder().toPath();
        commands = new CommandRunner(this, new CommandPolicy(c.getStringList("denied-commands")), c.getLong("command-timeout-ms"));
        game = new GameTools(this, c.getString("chat-prefix"), commands::timeoutMs);
        claude = new ClaudeClient(clientConfig(c), getLogger(), new ConversationStore(data.resolve("conversation.json")),
                new MemoryStore(data.resolve("memory")), commands, game, new WorldSnapshot(this));
        agent = new AgentService(claude, settings(c), Clock.systemUTC(), getLogger());
        agent.start();
        getServer().getPluginManager().registerEvents(new ChatTriggerListener(agent), this);
        getCommand("claude").setExecutor(new ClaudeCommand(this));
        events = eventTrigger(c);
        events.start();
        warnIfUnconfigured();
    }

    @Override
    public void onDisable() {
        if (events != null) events.stop();
        if (agent != null) agent.shutdown();
        if (claude != null) claude.close();
    }

    /** Re-reads config.yml and the system prompt; history is kept. Everything is built first, so a failure applies nothing. */
    synchronized void reload() throws Exception {
        FileConfiguration c = loadConfig();
        CommandPolicy policy = new CommandPolicy(c.getStringList("denied-commands"));
        ClaudeClient.Config client = clientConfig(c);
        AgentService.Settings agentSettings = settings(c);
        RandomEventTrigger next = eventTrigger(c);
        commands.configure(policy, c.getLong("command-timeout-ms"));
        game.setChatPrefix(c.getString("chat-prefix"));
        claude.reconfigure(client);
        agent.updateSettings(agentSettings);
        events.stop();
        events = next;
        events.start();
        warnIfUnconfigured();
    }

    RandomEventTrigger events() { return events; }

    AgentService agent() { return agent; }

    ClaudeClient claude() { return claude; }

    /** The data-folder config with the bundled config.yml as defaults, so keys missing from an old file still resolve. */
    private FileConfiguration loadConfig() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.load(new File(getDataFolder(), "config.yml"));
        try (Reader defaults = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            c.setDefaults(YamlConfiguration.loadConfiguration(defaults));
        }
        return c;
    }

    private RandomEventTrigger eventTrigger(FileConfiguration c) {
        return new RandomEventTrigger(this, agent, new RandomEventTrigger.Settings(c.getBoolean("events.enabled"),
                c.getLong("events.min-minutes"), c.getLong("events.max-minutes"), c.getStringList("events.themes")));
    }

    private AgentService.Settings settings(FileConfiguration c) {
        return new AgentService.Settings(c.getLong("batch-window-ms"), c.getInt("max-turns-per-minute"),
                c.getInt("max-queued-triggers"), !apiKey(c).isEmpty(), c.getInt("max-triggers-per-player-per-minute"));
    }

    private ClaudeClient.Config clientConfig(FileConfiguration c) {
        String effort = c.getString("effort").toLowerCase(Locale.ROOT);
        if (!EFFORTS.contains(effort)) {
            getLogger().warning("Unknown effort '" + effort + "' in config.yml; using medium.");
            effort = c.getDefaults().getString("effort");
        }
        return new ClaudeClient.Config(apiKey(c), c.getString("model"), effort,
                c.getInt("max-tool-iterations"), c.getInt("max-commands-per-call"), readSystemPrompt());
    }

    private static String apiKey(FileConfiguration c) {
        String env = System.getenv("ANTHROPIC_API_KEY");
        if (env != null && !env.isBlank()) return env.strip();
        return c.getString("api-key").strip();
    }

    private void warnIfUnconfigured() {
        if (!agent.isConfigured()) {
            getLogger().warning("No Anthropic API key: set api-key in config.yml or the ANTHROPIC_API_KEY environment variable, "
                    + "then run /claude reload. Chat is ignored until then.");
        }
    }

    private Path systemPromptFile() {
        return getDataFolder().toPath().resolve("system-prompt.md");
    }

    private String readSystemPrompt() {
        try (InputStream core = getResource("core-rules.md")) {
            return new String(core.readAllBytes(), StandardCharsets.UTF_8) + "\n\n" + Files.readString(systemPromptFile());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + systemPromptFile(), e);
        }
    }
}
