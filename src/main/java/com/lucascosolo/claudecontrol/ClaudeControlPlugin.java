package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClaudeControlPlugin extends JavaPlugin {
    private static final int JOURNAL_CAPACITY = 100;
    private AgentService agent;
    private WakeConditions wakes;
    private QuestTool quests;
    private AgentTurns turns;
    private CommandRunner commands;
    private GameTools game;
    private ToolDispatcher tools;
    private MemoryStore memory;
    private McpServer mcp;
    private WorkshopTools workshop;
    private PluginLibrary library;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask libraryTimer;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask changeLogTimer;
    private ChangeLog changeLog;
    private volatile RandomEventTrigger events;
    private PluginWatchService watch;
    private TurnIndicator indicator;
    private Routines routines;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask laterTimer;

    /** Everything a start or reload builds before any of it is applied; building writes nothing. */
    private record Prepared(PluginSettings s, boolean workshopMode, AgentTurns.Backends backends, String cliPrompt) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!Files.exists(systemPromptFile())) saveResource("system-prompt.md", false);
        PluginSettings s;
        try {
            s = PluginSettings.from(loadConfig(), getLogger());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load config.yml: " + e.getMessage(), e);
        }
        memory = new MemoryStore(migrateMemory());
        commands = new CommandRunner(this, new CommandPolicy(s.deniedCommands()), s.commandTimeoutMs());
        game = new GameTools(this, s.chatPrefix(), commands::timeoutMs);
        Path data = getDataFolder().toPath().toAbsolutePath();
        workshop = new WorkshopTools(this, data.resolve("agent").resolve("workshop"), data.getParent(), data.resolve("restart-history.txt"));
        library = new PluginLibrary(Clock.systemUTC(), data.resolve("plugin-library.json"));
        workshop.setLibrary(library);
        workshop.cleanStaleRevisions();
        workshop.recordShelvedAtBoot();
        workshop.reconcileLibrary();
        watch = new PluginWatchService(this, data.resolve("workshop-installs.json"), data.getParent().getParent().resolve("claude-quarantined-on-boot"));
        workshop.setWatchInfo(watch::describe);
        copyTemplate(data.resolve("agent").resolve("workshop").resolve("template"));
        tools = new ToolDispatcher(commands, game, workshop, s.maxCommandsPerCall(), () -> turns == null ? null : turns.currentContext(),
                watch::recorded);
        indicator = new TurnIndicator(this, game::chatPrefix);
        tools.setToolListener(indicator::tool);
        SuiteTools suite = new SuiteTools(this, commands::timeoutMs);
        routines = new Routines(this, suite, getLogger());
        LaterStore later = new LaterStore(Clock.systemUTC(), data.resolve("later.json"), getLogger());
        tools.setAudit(getLogger());
        tools.setOwners(() -> java.util.Set.copyOf(getConfig().getStringList("owners")));
        tools.setIsOp(n -> getServer().getOperators().stream().anyMatch(o -> n.equalsIgnoreCase(o.getName())));
        tools.register("spawn", new SpawnTool(suite));
        tools.setEntityTraits(new EntityTraitsTool(suite, suite.traits()));
        getServer().getPluginManager().registerEvents(suite.traits(), this);
        CoverTool cover = new CoverTool(suite, new CoverLifetimes(java.time.Clock.systemUTC(), data.resolve("cover-lifetimes.json")));
        tools.register("cover", cover);
        getServer().getPluginManager().registerEvents(cover, this);
        cover.resume();
        PlayerTool player = new PlayerTool(suite);
        tools.register("player", player);
        tools.register("show", new ShowTool(suite));
        tools.register("routine", new RoutineTool(routines));
        tools.register("later", new LaterTool(later));
        wakes = new WakeConditions(this, data, t -> agent != null && agent.submit(t), getLogger());
        tools.setWakeCreators(wakes::creatorsOf);
        tools.register("wake", new WakeTool(wakes));
        quests = new QuestTool(new QuestBook(Clock.systemUTC(), data.resolve("quests.json")), suite, player);
        tools.register("quest", quests);
        PlacesTool places = new PlacesTool(new PlaceBook(Clock.systemUTC(), data.resolve("places.json")), suite, game);
        tools.register("places", places);
        ContainerTool containers = new ContainerTool(suite);
        tools.register("container", containers);
        tools.register("book", new BookTool(suite, containers));
        ChangeLog changeLog = new ChangeLog(20, Clock.systemUTC(), data.resolve("changes.json"));
        tools.setChangeLog(changeLog);
        tools.register("undo", new UndoTool(suite, changeLog));
        tools.register("find", new FindTool(suite, places.book()));
        routines.setCalls((tool, args, authority, label, run) -> tools.execute(tool, args, authority, label, "routine", run));
        changeLogTimer = getServer().getAsyncScheduler().runAtFixedRate(this, t -> changeLog.flushIfDue(), 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        this.changeLog = changeLog;
        getServer().getPluginManager().registerEvents(suite, this);
        getServer().getPluginManager().registerEvents(routines, this);
        getServer().getPluginManager().registerEvents(wakes, this);
        getServer().getPluginManager().registerEvents(quests, this);
        getServer().getPluginManager().registerEvents(containers, this);
        wakes.start();
        laterTimer = getServer().getAsyncScheduler().runAtFixedRate(this, t -> runLater(later), 5, 1, java.util.concurrent.TimeUnit.SECONDS);
        libraryTimer = getServer().getAsyncScheduler().runAtFixedRate(this, t -> workshop.shelveExpired(), 10, 10, java.util.concurrent.TimeUnit.SECONDS);
        mcp = new McpServer(ToolCatalog.game(), this::mcpCall, s.mcpPort());
        WorldJournal journal = new WorldJournal(JOURNAL_CAPACITY, Clock.systemUTC());
        Prepared p;
        try {
            mcp.start();
            writeMcpConfig();
            p = prepare(s);
            writeCliFiles(p);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start the backends: " + e.getMessage(), e);
        }
        setWorkshopMode(s, p.workshopMode());
        WorldReport report = new WorldReport(this, journal);
        report.register(quests);
        report.register(wakes);
        report.register(places);
        report.register(LibraryReport.catalogue(library));
        tools.register("server_status", new StatusTool(this, () -> java.util.List.of(workshop.list().split("\n")), later::size,
                () -> wakes.store().list().size(), workshop::restartsLeft, java.util.List.of(quests, LibraryReport.summary(library))));
        turns = new AgentTurns(report, memory, p.backends(), indicator);
        agent = new AgentService(turns, s.agent(), Clock.systemUTC(), getLogger());
        agent.start();
        getServer().getAsyncScheduler().runNow(this, t -> turns.warm());
        getServer().getPluginManager().registerEvents(new ChatTriggerListener(agent), this);
        getServer().getPluginManager().registerEvents(new JournalListener(journal), this);
        getServer().getPluginManager().registerEvents(new BootGuardListener(data.getParent().getParent().resolve("claude-boot-pending"), getLogger()), this);
        getServer().getPluginManager().registerEvents(watch, this);
        watch.start();
        getCommand("claude").setExecutor(new ClaudeCommand(this));
        events = new RandomEventTrigger(this, agent, s.events());
        events.start();
        warnIfUnconfigured(s);
    }

    @Override
    public void onDisable() {
        if (laterTimer != null) laterTimer.cancel();
        if (libraryTimer != null) libraryTimer.cancel();
        if (changeLogTimer != null) changeLogTimer.cancel();
        if (changeLog != null) changeLog.flush();
        if (wakes != null) wakes.stop();
        if (quests != null) quests.stop();
        if (routines != null) routines.stopAll();
        if (events != null) events.stop();
        if (watch != null) watch.stop();
        if (agent != null) agent.shutdown();
        if (turns != null) turns.close();
        if (mcp != null) mcp.stop();
    }

    /** Runs the later entries that are due, each call through the dispatcher under the actor that scheduled it. */
    private void runLater(LaterStore later) {
        for (LaterStore.Entry e : later.due(java.time.Instant.now())) {
            int failed = 0;
            String actors = "unknown";
            for (com.fasterxml.jackson.databind.JsonNode c : e.calls()) {
                actors = c.path("actors").asText("unknown");
                Authority a = Authority.fromJson(c.path("authority").asText(null)).asDeferred();
                try {
                    ToolResult r = tools.execute(c.path("tool").asText(), c.path("args"), a, actors + " (later " + e.label() + ")");
                    if (r.isError()) {
                        failed++;
                        getLogger().info("later " + e.label() + ": " + c.path("tool").asText() + " failed: " + r.text());
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            getLogger().info(CommandRunner.clean("[audit] LATER " + e.label() + " for " + actors + " ran " + e.calls().size() + " call(s), " + failed + " failed"));
        }
    }

    /**
     * Re-reads config.yml and the system prompt. Everything that can fail is built first; files are written and settings applied
     * only after that, so a failed reload changes nothing. mcp.port takes effect only at start.
     */
    synchronized void reload() throws Exception {
        PluginSettings s = PluginSettings.from(loadConfig(), getLogger());
        CommandPolicy policy = new CommandPolicy(s.deniedCommands());
        Prepared p = prepare(s);
        writeCliFiles(p);
        commands.configure(policy, s.commandTimeoutMs());
        game.setChatPrefix(s.chatPrefix());
        tools.setMaxCommandsPerCall(s.maxCommandsPerCall());
        turns.replace(p.backends());
        getServer().getAsyncScheduler().runNow(this, t -> turns.warmPending());
        setWorkshopMode(s, p.workshopMode());
        watch.stop();
        watch.start();
        quests.stop();
        for (org.bukkit.entity.Player pl : getServer().getOnlinePlayers()) pl.getScheduler().run(this, t -> quests.refresh(pl), null);
        agent.updateSettings(s.agent());
        events.stop();
        events = new RandomEventTrigger(this, agent, s.events());
        events.start();
        warnIfUnconfigured(s);
    }

    RandomEventTrigger events() { return events; }

    AgentService agent() { return agent; }

    AgentTurns turns() { return turns; }

    WorkshopTools workshop() { return workshop; }

    PluginLibrary library() { return library; }



    /** workshop.enabled takes effect only inside a container; there is no override. */
    private boolean workshopMode(PluginSettings s) {
        boolean enabled = s.workshopEnabled(), container = Container.detected();
        getLogger().info("Container.detected=" + container + ", workshop.enabled=" + enabled + ", workshop mode "
                + (enabled && container ? "active" : "off") + ".");
        if (enabled && !container) getLogger().warning("workshop.enabled is true but the server is not running in a container; staying in game mode.");
        return enabled && container;
    }

    private void setWorkshopMode(PluginSettings s, boolean active) {
        workshop.configureRestarts(s.minMinutesBetweenRestarts(), s.maxRestartsPerDay());
        workshop.setMode(active, s.workshopEnabled()
                ? "Workshop mode is off: the server is not running in a container, so plugins cannot be written or installed."
                : "Workshop mode is off: the server owner has not enabled it (workshop.enabled).");
    }

    /** Copies the bundled plugin template on first start; the Gradle wrapper scripts are made executable. */
    private void copyTemplate(Path target) {
        if (Files.exists(target)) return;
        String prefix = "workshop-template/";
        try (var jar = new java.util.zip.ZipFile(getFile())) {
            for (var e : jar.stream().filter(e -> e.getName().startsWith(prefix) && !e.isDirectory()).toList()) {
                Path out = target.resolve(e.getName().substring(prefix.length()));
                Files.createDirectories(out.getParent());
                try (InputStream in = jar.getInputStream(e)) {
                    Files.copy(in, out);
                }
                if (out.getFileName().toString().startsWith("gradlew")) out.toFile().setExecutable(true, false);
            }
            getLogger().info("Copied the plugin template to " + target + ".");
        } catch (IOException e) {
            getLogger().warning("Cannot copy the plugin template: " + e.getMessage());
        }
    }

    private ToolResult mcpCall(String name, JsonNode args) {
        String actors = turns.currentActors();
        if (actors == null || !turns.inWindow()) return new ToolResult("No agent turn is in progress.", true);
        // Claude Code sends a nullable string as the text "null"; mean a broadcast unless a player is really called that.
        if (name.equals("send_chat") && args.path("player").asText("").equals("null") && getServer().getPlayerExact("null") == null) {
            args = ((com.fasterxml.jackson.databind.node.ObjectNode) args.deepCopy()).putNull("player");
        }
        try {
            turns.toolCalled();
            return tools.call(name, args, actors);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult("Interrupted.", true);
        }
    }

    /** Builds the backends and the CLI prompt text; writes nothing. */
    private Prepared prepare(PluginSettings s) throws IOException {
        Path data = getDataFolder().toPath().toAbsolutePath();
        boolean workshopMode = workshopMode(s);
        ApiBackend api = new ApiBackend(new ApiBackend.Config(s.apiKey(), s.model(), s.effort(), s.maxToolIterations(), readSystemPrompt(false, false)),
                getLogger(), new ConversationStore(data.resolve("conversation.json")), memory, tools, ToolCatalog.game());
        if (s.backend().equals("api")) {
            return new Prepared(s, workshopMode, new AgentTurns.Backends(new FallbackBackend(api, null, Clock.systemUTC(), getLogger()), null), null);
        }
        PluginSettings.Cli c = s.cli();
        CliBackend cli = new CliBackend(new CliBackend.Settings(c.binary(), c.commandPrefix(), c.model(), c.effort(), c.turnTimeoutSeconds(),
                c.oauthToken(), cliHome(s), data.resolve("agent"), data.resolve("cli-run").resolve("system-prompt.md"),
                data.resolve("cli-run").resolve("mcp.json"), data.resolve("cli-session-id"), ToolCatalog.game().names(), workshopMode), getLogger());
        cli.setToolListener(call -> indicator.tool(call.name(), call.command()));
        cli.setWindowListener(open -> { if (turns != null) turns.setWindow(open); });
        return new Prepared(s, workshopMode, new AgentTurns.Backends(new FallbackBackend(cli, s.apiKey().isEmpty() ? null : api, Clock.systemUTC(),
                getLogger()), cli), readSystemPrompt(true, workshopMode));
    }

    private Path cliHome(PluginSettings s) {
        return s.cli().configDir().isEmpty() ? getDataFolder().toPath().toAbsolutePath().resolve("cli-home") : Path.of(s.cli().configDir());
    }

    /**
     * The CLI prompt and the CLI's user settings, written by the server user so the agent session cannot change them (the
     * container makes cli-home sticky). Hooks are off, so nothing in settings can run a command.
     */
    private void writeCliFiles(Prepared p) throws IOException {
        if (p.cliPrompt() == null) return;
        writePrivate(getDataFolder().toPath().toAbsolutePath().resolve("cli-run").resolve("system-prompt.md"), p.cliPrompt());
        writePrivate(cliHome(p.s()).resolve("settings.json"), CliArgs.userSettings());
    }

    /** The agent's working directory holds memory/; the memory directory of v0.1 moves there once. */
    private Path migrateMemory() {
        Path data = getDataFolder().toPath(), old = data.resolve("memory"), now = data.resolve("agent").resolve("memory");
        try {
            if (Files.isDirectory(old) && !Files.exists(now)) {
                Files.createDirectories(now.getParent());
                Files.move(old, now);
                getLogger().info("Moved the memory directory to " + now + ".");
            } else if (Files.isDirectory(old)) {
                getLogger().warning("Both " + old + " and " + now + " exist; using " + now + " and leaving the old one alone.");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot move " + old + " to " + now + ": " + e.getMessage(), e);
        }
        return now;
    }

    private void writeMcpConfig() throws IOException {
        var root = new ObjectMapper().createObjectNode();
        var game = root.putObject("mcpServers").putObject("game").put("type", "http").put("url", "http://127.0.0.1:" + mcp.port() + "/mcp");
        game.putObject("headers").put("Authorization", "Bearer " + mcp.token());
        writePrivate(getDataFolder().toPath().toAbsolutePath().resolve("cli-run").resolve("mcp.json"), root.toString());
    }

    /** Owner read-write, group read (the container's agent user reads cli-run/ through its group). */
    private static void writePrivate(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        }
        Files.writeString(file, text);
    }

    /** The data-folder config with the bundled config.yml as defaults, so keys missing from an old file still resolve. */
    private FileConfiguration loadConfig() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.load(new File(getDataFolder(), "config.yml"));
        try (Reader defaults = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            c.setDefaults(YamlConfiguration.loadConfiguration(defaults));
        }
        return c;
    }

    private void warnIfUnconfigured(PluginSettings s) {
        if (!s.agent().configured()) {
            getLogger().warning("backend is api but there is no Anthropic API key: set api-key in config.yml or the ANTHROPIC_API_KEY "
                    + "environment variable, then run /claude reload. Chat is ignored until then.");
        }
    }

    private Path systemPromptFile() {
        return getDataFolder().toPath().resolve("system-prompt.md");
    }

    /** Core rules, then the owner's notes, then (Claude Code only) the CLI rules, whose Workshop section only in workshop mode. */
    private String readSystemPrompt(boolean cli, boolean workshopMode) {
        try {
            String prompt = resource("core-rules.md") + "\n\n" + Files.readString(systemPromptFile());
            if (!cli) return prompt;
            return prompt + "\n\n" + resource("cli-rules.md") + "\n\n" + resource(workshopMode ? "workshop-rules.md" : "workshop-off-rules.md");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + systemPromptFile(), e);
        }
    }

    private String resource(String name) throws IOException {
        try (InputStream in = getResource(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
