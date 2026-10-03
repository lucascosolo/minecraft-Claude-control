package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Running routines. Each followed player gets a fixed-rate task on their own scheduler (an at-place routine uses the global
 * one); the task only hands the step work to the async scheduler, which waits on the regions it spawns into. No model is involved.
 */
final class Routines implements org.bukkit.event.Listener {
    static final int MAX_RUNNING = 3;
    private static final ObjectMapper M = new ObjectMapper();

    /** ToolDispatcher.execute: no indicator, no per-call audit, gated by the stored authority. */
    interface Calls { ToolResult execute(String tool, JsonNode args, Authority authority, String label, String run) throws InterruptedException; }

    private final class Run {
        final String run = java.util.UUID.randomUUID().toString();
        final RoutineSpec spec;
        final String actor;
        final Authority authority;
        final Instant ends;
        final AliveCap cap;
        final Map<String, ScheduledTask> tasks = new ConcurrentHashMap<>();
        final Map<UUID, String> owner = new ConcurrentHashMap<>();
        final AtomicInteger spawned = new AtomicInteger();
        final Set<String> busy = ConcurrentHashMap.newKeySet();
        final AtomicBoolean ended = new AtomicBoolean();
        volatile String lastError;
        volatile boolean notRecorded;
        volatile boolean halved;
        final AtomicInteger beats = new AtomicInteger();

        Run(RoutineSpec spec, Authority authority, String actor) {
            this.spec = spec;
            this.authority = authority;
            this.actor = actor;
            this.ends = Instant.now().plusSeconds(spec.seconds());
            this.cap = new AliveCap(spec.maxAlive(), java.time.Clock.systemUTC());
        }
    }

    private final Plugin plugin;
    private final SuiteTools suite;
    private final Logger log;
    private final Map<String, Run> running = new LinkedHashMap<>();
    private volatile Calls calls;

    Routines(Plugin plugin, SuiteTools suite, Logger log) {
        this.plugin = plugin;
        this.suite = suite;
        this.log = log;
    }

    void setCalls(Calls calls) { this.calls = calls; }

    synchronized String start(RoutineSpec s, Authority scheduling, String actor) throws GameTools.ToolError {
        Authority a = scheduling.asDeferred();
        if (running.containsKey(s.label())) throw new GameTools.ToolError("a routine labelled " + s.label() + " is already running; stop it or pick another label.");
        if (running.size() >= MAX_RUNNING) throw new GameTools.ToolError("three routines are already running (" + String.join(", ", running.keySet()) + "); stop one first.");
        List<Player> players = new ArrayList<>();
        if (s.at() == null) {
            if (s.followAll()) players.addAll(Bukkit.getOnlinePlayers());
            else for (String n : s.follow()) {
                Player p = Bukkit.getPlayerExact(n);
                if (p == null) throw new GameTools.ToolError("Player " + n + " is not online.");
                players.add(p);
            }
            if (players.isEmpty()) throw new GameTools.ToolError("no players online to follow.");
        }
        for (RoutineSpec.Step st : s.steps()) {
            if (!st.tool().equals("spawn")) continue;
            String who = players.isEmpty() ? null : players.getFirst().getName();
            try {
                SuiteTools.check(SpawnSpec.parse(who == null ? st.args() : substitute(st.args(), who)), a);
            } catch (IllegalArgumentException e) {
                throw new GameTools.ToolError("spawn step: " + e.getMessage());
            }
        }
        Run r = new Run(s, a, actor);
        running.put(s.label(), r);
        if (s.at() == null) {
            for (Player p : players) follow(r, p);
        } else {
            r.tasks.put("", Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, x -> beat(r, null), 1, s.everyTicks()));
        }
        log.info(CommandRunner.clean("[audit] ROUTINE " + s.label() + " for " + actor + ": " + s.seconds() + " s every " + s.everyTicks()
                + " ticks, " + (s.at() == null ? "following " + String.join(", ", r.tasks.keySet()) : "at a place")));
        return M.createObjectNode().put("label", s.label()).put("seconds", s.seconds()).put("every_ticks", s.everyTicks())
                .put("max_alive", s.maxAlive()).put("following", s.at() == null ? String.join(", ", r.tasks.keySet()) : "a fixed place")
                .put("note", "Runs on its own; totals arrive in the next world report.").toString();
    }

    private void follow(Run r, Player p) {
        ScheduledTask t = p.getScheduler().runAtFixedRate(plugin, x -> beat(r, p.getName()), () -> left(r, p.getName()), 1, r.spec.everyTicks());
        if (t != null) r.tasks.put(p.getName(), t);
    }

    /** follow: "all" includes players who join while the routine runs (A2). */
    @org.bukkit.event.EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        List<Run> all;
        synchronized (this) {
            all = running.values().stream().filter(r -> r.spec.followAll() && r.spec.at() == null).toList();
        }
        for (Run r : all) if (!r.ended.get() && !r.tasks.containsKey(e.getPlayer().getName())) follow(r, e.getPlayer());
    }

    /** On the follower's (or the global) tick thread: only hand the work to the async scheduler, one beat at a time per player. */
    private void beat(Run r, String player) {
        if (r.ended.get()) return;
        if (Instant.now().isAfter(r.ends)) {
            Bukkit.getAsyncScheduler().runNow(plugin, x -> end(r.spec.label(), "finished"));
            return;
        }
        r.halved = lagging(r, player);
        if (r.halved && r.beats.incrementAndGet() % 2 == 1) return;
        String key = player == null ? "" : player;
        if (!r.busy.add(key)) return;
        Bukkit.getAsyncScheduler().runNow(plugin, x -> {
            try {
                steps(r, player);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                r.lastError = e.getMessage();
            } finally {
                r.busy.remove(key);
            }
        });
    }

    /** True when the region this beat works in runs under 15 TPS (Folia's per-region figure, last 5 seconds). */
    private static boolean lagging(Run r, String player) {
        try {
            double[] tps;
            if (player != null) {
                Player p = Bukkit.getPlayerExact(player);
                if (p == null) return false;
                tps = Bukkit.getRegionTPS(p.getLocation());
            } else {
                PlaceExpr.Point a = r.spec.at().absolute();
                tps = Bukkit.getRegionTPS(Bukkit.getWorld(r.spec.at().world()), (int) Math.floor(a.x()) >> 4, (int) Math.floor(a.z()) >> 4);
            }
            return tps != null && tps.length > 0 && tps[0] < 15;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void steps(Run r, String player) throws Exception {
        suite.remove(uuids(r.cap.expired()));
        for (RoutineSpec.Step st : r.spec.steps()) {
            if (r.ended.get()) return;
            if (st.chance() < 1 && ThreadLocalRandom.current().nextDouble() >= st.chance()) continue;
            JsonNode args = player == null ? st.args() : substitute(st.args(), player);
            if (st.tool().equals("spawn")) {
                SpawnSpec spec;
                try {
                    spec = SpawnSpec.parse(args);
                } catch (IllegalArgumentException e) {
                    throw new GameTools.ToolError(e.getMessage());
                }
                SuiteTools.Spawned s = suite.spawnNow(spec, r.authority);
                if (r.ended.get()) {
                    suite.remove(s.ids());
                    return;
                }
                Duration life = SuiteTools.seconds(spec.lifetimeSeconds());
                for (UUID id : s.ids()) {
                    r.owner.put(id, player == null ? "" : player);
                    r.spawned.incrementAndGet();
                    List<UUID> over = uuids(r.cap.add(id.toString(), life));
                    over.forEach(r.owner::remove);
                    suite.remove(over);
                }
            } else if (calls != null) {
                ToolResult res = calls.execute(st.tool(), args, r.authority, r.actor + " (routine " + r.spec.label() + ")", r.run);
                if (res.isError()) r.lastError = res.text();
                else if (res.text().contains(ChangeLog.NOT_RECORDED_NOTE)) r.notRecorded = true;
            }
        }
    }

    private static List<UUID> uuids(List<String> ids) {
        return ids.stream().map(UUID::fromString).toList();
    }

    /** Replaces the "$player" placeholder anywhere in the step arguments. */
    static JsonNode substitute(JsonNode n, String player) {
        if (n.isTextual() && n.asText().equals("$player")) return M.getNodeFactory().textNode(player);
        if (n.isObject()) {
            ObjectNode o = M.createObjectNode();
            n.properties().forEach(e -> o.set(e.getKey(), substitute(e.getValue(), player)));
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = M.createArrayNode();
            n.forEach(x -> a.add(substitute(x, player)));
            return a;
        }
        return n;
    }

    /** A followed player's task retired (they quit): drop their part and what it spawned; end the routine when nobody is left. */
    private void left(Run r, String player) {
        r.tasks.remove(player);
        List<UUID> theirs = new ArrayList<>();
        r.owner.forEach((id, p) -> { if (p.equals(player)) theirs.add(id); });
        theirs.forEach(id -> { r.owner.remove(id); r.cap.release(id.toString()); });
        suite.remove(theirs);
        if (r.tasks.isEmpty()) Bukkit.getAsyncScheduler().runNow(plugin, x -> end(r.spec.label(), "nobody left to follow"));
    }

    String stop(String label) throws GameTools.ToolError {
        if (!end(label, "stopped")) throw new GameTools.ToolError("no routine labelled " + label + " is running.");
        return M.createObjectNode().put("stopped", label).toString();
    }

    boolean end(String label, String why) { return end(label, why, true); }

    /**
     * Ends a routine: cancels its tasks and schedules removal of what it spawned (never blocks), then runs its on_end calls,
     * which wait on regions — so with onEnd true this is called off tick threads only.
     */
    private boolean end(String label, String why, boolean onEnd) {
        Run r;
        synchronized (this) {
            r = running.remove(label);
        }
        if (r == null || !r.ended.compareAndSet(false, true)) return false;
        r.tasks.values().forEach(ScheduledTask::cancel);
        suite.remove(new ArrayList<>(r.owner.keySet()));
        log.info("[audit] ROUTINE " + label + " ended (" + why + "), " + r.spawned.get() + " spawned in total");
        if (onEnd && calls != null && r.spec.onEnd() != null) {
            for (RoutineSpec.Step st : r.spec.onEnd()) {
                try {
                    calls.execute(st.tool(), st.args(), r.authority, r.actor + " (routine " + label + " end)", r.run + " end");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return true;
    }

    synchronized String list() {
        ArrayNode a = M.createArrayNode();
        for (Run r : running.values()) {
            ObjectNode o = a.addObject().put("label", r.spec.label()).put("seconds_left", Math.max(0, Duration.between(Instant.now(), r.ends).toSeconds()))
                    .put("alive", r.cap.size()).put("spawned", r.spawned.get())
                    .put("following", r.spec.at() == null ? String.join(", ", r.tasks.keySet()) : "a fixed place");
            if (r.halved) o.put("rate", "halved: the region is lagging");
            if (r.notRecorded) o.put("undo_journal", "changes are no longer recorded");
            if (r.lastError != null) o.put("last_error", r.lastError.length() > 200 ? r.lastError.substring(0, 200) : r.lastError);
        }
        return M.createObjectNode().set("routines", a).toString();
    }

    void stopAll() {
        List<String> labels;
        synchronized (this) {
            labels = new ArrayList<>(running.keySet());
        }
        labels.forEach(l -> end(l, "server stopping", false));
    }

    synchronized Set<String> labels() { return new HashSet<>(running.keySet()); }
}
