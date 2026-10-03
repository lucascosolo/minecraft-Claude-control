package com.lucascosolo.claudecontrol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Claude Code as the brain: one long-lived claude process, one stream-json user line per turn, game tools over MCP. */
public final class CliBackend implements AgentBackend {
    public record Settings(String binary, List<String> commandPrefix, String model, String effort, int turnTimeoutSeconds,
                           String oauthToken, Path configDir, Path workDir, Path promptFile, Path mcpConfig, Path sessionIdFile,
                           List<String> gameTools, boolean workshop) {
        @Override
        public String toString() {
            return "Settings[binary=" + binary + ", model=" + model + ", effort=" + effort + ", workshop=" + workshop + ", oauthToken=<redacted>]";
        }
    }

    private static final class ExitedBeforeInit extends Exception {
        ExitedBeforeInit(String message) { super(message); }
    }

    private final Settings s;
    private final Logger log;
    private volatile CliSession session;
    private volatile boolean closed;
    private volatile String sessionId;
    private volatile Double lastCost;

    public CliBackend(Settings settings, Logger log) {
        this.s = settings;
        this.log = log;
        this.sessionId = readId();
    }

    /** Set by any game tool call during the turn; a turn that then fails is abandoned, never replayed elsewhere. */
    private volatile boolean toolsRan;

    void toolCalled() { toolsRan = true; }

    private volatile java.util.function.Consumer<CliEvents.ToolCall> toolUse = n -> {};

    /** Told the name of each tool the session starts (from its assistant messages), e.g. Bash. */
    void setToolListener(java.util.function.Consumer<CliEvents.ToolCall> listener) { this.toolUse = listener; }

    private volatile java.util.function.Consumer<Boolean> window = b -> {};

    /** Told true once a turn's line was written to the CLI and false when its result arrives (A14). */
    void setWindowListener(java.util.function.Consumer<Boolean> listener) { this.window = listener; }

    /** Starts the process if none is running; failures are logged and the first turn tries again. */
    synchronized void warm() {
        if (closed || session != null) return;
        try {
            session = start(readId());
            log.info("Claude Code warm start: the process is running before the first turn.");
        } catch (RuntimeException e) {
            log.warning("Claude Code warm start failed (" + e.getMessage() + "); the first turn will try again.");
        }
    }

    /** A failure after a tool call ran becomes TurnAbandoned so the fallback does not run the tools a second time. */
    private RuntimeException failed(String why) {
        return toolsRan ? new TurnAbandoned(why + " after game tools had already run") : new BackendUnavailable(why);
    }

    @Override
    public synchronized void runTurn(String userTurn, String actors) throws Exception {
        if (closed) throw new IllegalStateException("The Claude Code backend is shut down.");
        toolsRan = false;
        long started = System.nanoTime();
        CliEvents.Result r = turn(userTurn);
        lastCost = r.costUsd();
        log.info(String.format(Locale.ROOT, "Turn served by cli in %d ms, cost $%.4f, session %s%s",
                (System.nanoTime() - started) / 1_000_000, r.costUsd(), shortId(sessionId), r.isError() ? " (error)" : ""));
        if (!r.isError()) return;
        String lower = r.text().toLowerCase(Locale.ROOT);
        if (lower.contains("/login") || lower.contains("not logged in") || lower.contains("authentication") || lower.contains("api key")) {
            kill();
            throw new BackendUnavailable("Claude Code is not authenticated: " + r.text());
        }
        throw new IllegalStateException("Claude Code turn failed (" + r.subtype() + "): " + r.text());
    }

    /** Sends the turn, starting the process if needed; a resume that fails before init is retried once as a fresh session. */
    private CliEvents.Result turn(String userTurn) throws InterruptedException {
        if (session == null) session = start(readId());
        try {
            return exchange(userTurn);
        } catch (ExitedBeforeInit e) {
            String resumed = session == null ? null : session.resumeId();
            kill();
            if (resumed == null) throw new BackendUnavailable("Claude Code exited before starting (" + e.getMessage() + ")");
            log.warning("Could not resume Claude Code session " + shortId(resumed) + " (" + e.getMessage() + "); starting a fresh session.");
            deleteId();
            session = start(null);
            try {
                return exchange(userTurn);
            } catch (ExitedBeforeInit again) {
                kill();
                throw new BackendUnavailable("Claude Code exited before starting (" + again.getMessage() + ")");
            }
        }
    }

    private CliEvents.Result exchange(String userTurn) throws InterruptedException, ExitedBeforeInit {
        CliSession cs = session;
        try {
            cs.send(userTurn);
            window.accept(true);
        } catch (IOException e) {
            // the process is gone; its EOF event follows
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(s.turnTimeoutSeconds());
        while (true) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) {
                kill();
                throw failed("no result within " + s.turnTimeoutSeconds() + " s; the claude process was killed");
            }
            CliEvents.Event ev = cs.next(left);
            if (ev == null) continue;
            if (ev == CliSession.EOF) {
                if (closed) throw new IllegalStateException("The Claude Code backend is shutting down.");
                String why = cs.exitSummary();
                if (!cs.initialized()) throw new ExitedBeforeInit(why);
                kill();
                throw failed("Claude Code exited mid-turn (" + why + ")");
            }
            switch (ev) {
                case CliEvents.Init i -> {
                    cs.markInitialized();
                    remember(i.sessionId());
                }
                case CliEvents.Result r -> {
                    window.accept(false);
                    if (r.isError() && !cs.initialized() && cs.resumeId() != null) throw new ExitedBeforeInit(r.text());
                    remember(r.sessionId());
                    return r;
                }
                default -> {}
            }
        }
    }

    private CliSession start(String resume) {
        List<String> cmd = new ArrayList<>(s.commandPrefix());
        cmd.addAll(CliArgs.build(new CliArgs.Options(s.binary(), s.model(), s.effort(), s.promptFile(), s.mcpConfig(), resume, s.workshop(),
                s.gameTools(), s.workDir())));
        Map<String, String> env = new HashMap<>();
        env.put("CLAUDE_CONFIG_DIR", s.configDir().toString());
        if (!s.oauthToken().isBlank()) env.put("CLAUDE_CODE_OAUTH_TOKEN", s.oauthToken());
        try {
            Files.createDirectories(s.workDir());
            Files.createDirectories(s.configDir());
            CliSession cs = new CliSession(cmd, s.workDir(), env, resume, log, c -> {
                if (c.command() != null) log.info(CommandRunner.clean("[audit] CLI " + c.name() + ": " + abbreviate(c.command())));
                toolUse.accept(c);
            });
            log.info("Claude Code started (pid " + cs.pid() + ", " + (resume == null ? "new session" : "--resume " + resume)
                    + ", model " + s.model() + ").");
            return cs;
        } catch (IOException e) {
            throw new BackendUnavailable("cannot start " + s.binary() + ": " + e.getMessage());
        }
    }

    private void remember(String id) {
        if (id == null || id.equals(sessionId)) return;
        sessionId = id;
        try {
            Files.writeString(s.sessionIdFile(), id);
        } catch (IOException e) {
            log.warning("Cannot store the Claude Code session id: " + e.getMessage());
        }
    }

    private String readId() {
        try {
            if (!Files.exists(s.sessionIdFile())) return null;
            String id = Files.readString(s.sessionIdFile()).strip();
            return id.isEmpty() ? null : id;
        } catch (IOException e) {
            return null;
        }
    }

    private void deleteId() {
        sessionId = null;
        try {
            Files.deleteIfExists(s.sessionIdFile());
        } catch (IOException e) {
            log.warning("Cannot delete " + s.sessionIdFile() + ": " + e.getMessage());
        }
    }

    private void kill() {
        CliSession cs = session;
        session = null;
        if (cs != null) cs.close();
    }

    @Override
    public synchronized void reset() {
        kill();
        deleteId();
        lastCost = null;
    }

    /** Not synchronized: it must interrupt a running turn by killing the process. */
    @Override
    public void close() {
        closed = true;
        kill();
    }

    @Override
    public void stop() { close(); }

    @Override
    public String describe() {
        Double cost = lastCost;
        return "cli (model " + s.model() + ", session " + shortId(sessionId)
                + (cost == null ? "" : String.format(Locale.ROOT, ", last turn $%.4f", cost)) + ")";
    }

    private static String shortId(String id) {
        return id == null ? "none" : id.substring(0, Math.min(8, id.length()));
    }

    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 300 ? one.substring(0, 300) + "…" : one;
    }
}
