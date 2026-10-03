package com.lucascosolo.claudecontrol;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The agent worker's turn runner: renders the batch into one user turn (with the memory files on the first turn after a start,
 * reset or reload) and hands it to the current backend. Backends built by a reload are swapped in on the worker thread, between turns.
 */
final class AgentTurns implements AgentService.TurnRunner {
    /** cli is the Claude Code backend inside turns, or null when it is not configured. */
    record Backends(AgentBackend turns, CliBackend cli) {
        void close() { turns.close(); }
    }

    static final int MEMORY_CAP = 16_000;
    private final WorldReport report;
    private final MemoryStore memory;
    private volatile Backends current;
    private Backends pending;
    private volatile boolean memoryPending = true;
    private volatile TurnContext context;
    private final Handoff handoff = new Handoff();
    private final TurnIndicator indicator;

    AgentTurns(WorldReport report, MemoryStore memory, Backends backends, TurnIndicator indicator) {
        this.indicator = indicator;
        this.report = report;
        this.memory = memory;
        this.current = backends;
    }

    /** Takes effect before the next turn or reset; a replaced pending set is closed unused. */
    synchronized void replace(Backends next) {
        if (pending != null) pending.close();
        pending = next;
    }

    private synchronized Backends apply() {
        if (pending != null) {
            Backends old = current;
            current = pending;
            pending = null;
            memoryPending = true;
            old.close();
        }
        return current;
    }

    @Override
    public void run(List<Trigger> batch) throws Exception {
        Backends b = apply();
        String who = batch.stream().map(t -> t.actor() == null ? t.source() : t.actor()).distinct().collect(Collectors.joining(","));
        String serving = (b.turns() instanceof FallbackBackend f ? f.next() : b.turns()).getClass().getSimpleName();
        boolean handingOff = handoff.changes(serving);
        boolean withMemory = memoryPending;
        memoryPending = false;
        context = new TurnContext(who, List.copyOf(batch));
        WorldReport.Capture capture = null;
        indicator.start(batch.stream().map(Trigger::actor).filter(java.util.Objects::nonNull).distinct().toList());
        String failure = null;
        try {
            capture = report.capture(withMemory && !handingOff ? memoryBlock() : null);
            String turn = capture.text() + "<events>\n" + TriggerBatch.render(batch) + "</events>";
            b.turns().runTurn(handoff.wrap(serving, turn, handingOff ? memoryBlock() : null), who);
        } catch (Exception | Error e) {
            if (withMemory) memoryPending = true;
            if (capture != null) report.restore(capture.drained());
            failure = e instanceof TurnAbandoned ? "My last answer was cut short; some of it may already have happened. Ask again if something is missing."
                    : "Sorry, I could not finish that. Try asking again in a moment.";
            throw e;
        } finally {
            indicator.end(failure);
            window = false;
            context = null;
        }
    }

    @Override
    public void reset() {
        Backends b = apply();
        b.turns().reset();
        memoryPending = true;
        warm(b);
    }

    /** Starts the CLI process now so the first turn does not pay for it (no prompt is sent, so nothing is spent). */
    void warm() { warm(current); }

    /** For a reload: the backends built but not yet swapped in are warmed so the next turn finds them running. */
    void warmPending() {
        Backends p;
        synchronized (this) { p = pending; }
        if (p != null) warm(p);
    }

    private static void warm(Backends b) {
        if (b.cli() != null) b.cli().warm();
    }

    @Override
    public void stop() { current.turns().stop(); }

    synchronized void close() {
        current.close();
        if (pending != null) pending.close();
        pending = null;
    }

    /** The actors of the turn in progress, or null between turns (MCP tool calls are refused then). */
    /** An MCP tool call reached the game during the turn in progress. */
    void toolCalled() {
        CliBackend cli = current.cli();
        if (cli != null) cli.toolCalled();
    }

    String currentActors() {
        TurnContext c = context;
        return c == null ? null : c.actors();
    }

    /** The turn in progress, or null between turns. Set before the backend runs and cleared after, so any thread may read it. */
    TurnContext currentContext() { return context; }

    private volatile boolean window;

    /** A14: MCP tool calls are accepted only between the turn's line reaching the CLI and its result arriving. */
    void setWindow(boolean open) { window = open && context != null; }

    boolean inWindow() { return window; }

    String describe() {
        Backends b = current;
        String served = b.turns().describe();
        return b.cli() == null || served.startsWith("cli") ? served : served + "; " + b.cli().describe();
    }

    private String memoryBlock() {
        try {
            return memory.dump(MEMORY_CAP);
        } catch (MemoryStore.MemoryException e) {
            return "(memory unavailable: " + e.getMessage() + ")";
        }
    }
}
