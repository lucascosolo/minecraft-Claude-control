package com.lucascosolo.claudecontrol;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * The single registry the listeners feed (A13): Bukkit events become WakeEvents, which the WakeStore matches on one worker
 * thread. A listener enqueues nothing unless a wake of its kind exists. Timers and the day/night phase are polled from the
 * global scheduler. A fired wake becomes a trigger with source "wake" and no actor; its creators are kept for "who asked".
 */
final class WakeConditions implements Listener, ReportSection {
    private static final Pattern ID = Pattern.compile("^wake (\\d+):");
    private final Plugin plugin;
    private final WakeStore store;
    private final Predicate<Trigger> submit;
    private final Logger log;
    private final Map<Integer, List<String>> fired = new LinkedHashMap<>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, List<String>> e) { return size() > 50; }
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ClaudeControl-wakes").daemon(true).factory());
    private volatile Set<String> kinds;
    private ScheduledTask poll;

    WakeConditions(Plugin plugin, Path dataDir, Predicate<Trigger> submit, Logger log) {
        this.plugin = plugin;
        this.store = new WakeStore(Clock.systemUTC(), dataDir.resolve("wakes.json"));
        this.submit = submit;
        this.log = log;
        try {
            Files.deleteIfExists(dataDir.resolve("wake-authority.json"));
        } catch (java.io.IOException ignored) {
            // B1: authority now lives in the wake entry; the old file is unused either way
        }
        kinds = store.watchedKinds();
    }

    WakeStore store() { return store; }

    void start() {
        poll = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            List<WakeEvent> times = kinds.contains("time")
                    ? Bukkit.getWorlds().stream().map(w -> WakeEvent.timeChanged(w.getName(), w.getTime())).toList() : List.of();
            submitWork(() -> {
                Instant now = Instant.now();
                for (WakeStore.Fired f : store.retry(now, this::deliver)) audit(f, "dropped: no turn could be queued for 10 minutes");
                fire(store.due(now));
                for (WakeEvent e : times) fire(store.match(e));
                kinds = store.watchedKinds();
            });
        }, 40, 20);
    }

    /** B8: stops the poll and waits up to 2 s for the worker. */
    void stop() {
        if (poll != null) poll.cancel();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Matching writes the store file, so it never runs on a tick thread; one worker keeps each player's samples in order. */
    private void submitWork(Runnable r) {
        try {
            worker.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    log.warning("Wake check failed: " + e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    /** Adds a wake; the players of the creating turn are stored with it (B1) for audit and "who asked" checks. */
    int add(WakeSpec spec, Authority a) {
        int id = store.add(spec, a.players());
        kinds = store.watchedKinds();
        return id;
    }

    /** The creators behind a wake trigger, or an empty list when the trigger is not a wake we fired. */
    synchronized List<String> creatorsOf(Trigger t) {
        if (!"wake".equals(t.source())) return List.of();
        Matcher m = ID.matcher(t.text());
        if (!m.find()) return List.of();
        return fired.getOrDefault(Integer.parseInt(m.group(1)), List.of());
    }

    private synchronized boolean deliver(WakeStore.Fired f) {
        fired.put(f.id(), f.creators());
        return submit.test(new Trigger("wake", null, false, f.text(), Instant.now()));
    }

    private void fire(List<WakeStore.Fired> list) {
        Instant now = Instant.now();
        for (WakeStore.Fired f : list) {
            boolean ok = deliver(f);
            if (!ok) store.hold(f, now);
            audit(f, ok ? "" : "turn not queued yet (paused, full or throttled); retrying for up to 10 minutes");
        }
    }

    private void audit(WakeStore.Fired f, String extra) {
        log.info(CommandRunner.clean("[audit] WAKE " + f.id() + " for " + (f.creators().isEmpty() ? "server" : String.join(",", f.creators()))
                + " fired: " + f.what() + (extra.isEmpty() ? "" : " (" + extra + ")")));
    }

    private void offer(WakeEvent e) { submitWork(() -> fire(store.match(e))); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!kinds.contains("moved") || !e.hasChangedBlock()) return;
        var to = e.getTo();
        offer(WakeEvent.playerMoved(e.getPlayer().getName(), to.getWorld().getName(), to.getX(), to.getY(), to.getZ()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (kinds.contains("joined")) offer(WakeEvent.playerJoined(e.getPlayer().getName()));
        if (kinds.contains("moved")) {
            var l = e.getPlayer().getLocation();
            offer(WakeEvent.playerMoved(e.getPlayer().getName(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        String name = e.getPlayer().getName();
        submitWork(() -> store.forget(name));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (kinds.contains("pickup") && e.getEntity() instanceof Player p)
            offer(WakeEvent.itemPickedUp(p.getName(), e.getItem().getItemStack().getType().getKey().getKey()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!kinds.contains("break")) return;
        var b = e.getBlock();
        offer(WakeEvent.blockBroken(e.getPlayer().getName(), b.getType().getKey().getKey(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!kinds.contains("place")) return;
        var b = e.getBlock();
        offer(WakeEvent.blockPlaced(e.getPlayer().getName(), b.getType().getKey().getKey(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        if (!kinds.contains("kill") || e.getEntity() instanceof Player) return;
        Player k = e.getEntity().getKiller();
        offer(WakeEvent.mobKilled(e.getEntity().getType().getKey().getKey(), k == null ? null : k.getName()));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { if (kinds.contains("died")) offer(WakeEvent.playerDied(e.getPlayer().getName())); }

    /** B7: a double chest reports both halves, so a wake at either one matches. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!kinds.contains("opened") || !(e.getPlayer() instanceof Player p)) return;
        List<Block> blocks = switch (e.getInventory().getHolder(false)) {
            case DoubleChest dc when dc.getLeftSide(false) instanceof Container l && dc.getRightSide(false) instanceof Container r -> List.of(l.getBlock(), r.getBlock());
            case Container c -> List.of(c.getBlock());
            case null, default -> List.of();
        };
        for (Block b : blocks) offer(WakeEvent.containerOpened(p.getName(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    @Override
    public List<String> reportLines() {
        List<WakeStore.Wake> list = store.list();
        if (list.isEmpty()) return List.of();
        StringBuilder sb = new StringBuilder("Wakes waiting: " + list.size() + " (");
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            WakeSpec s = list.get(i).spec();
            sb.append(i == 0 ? "" : "; ").append(list.get(i).id()).append(" ").append(s.isTimer() ? "timer" : s.type());
        }
        return List.of(sb.append(list.size() > 5 ? "; ...)" : ")").toString());
    }
}
