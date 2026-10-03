package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/** cover: scatter plants or snow on the ground around a place, per chunk on its region, recorded for undo (D12). */
final class CoverTool implements SuiteTool, Listener {
    private static final ObjectMapper M = new ObjectMapper();
    private final SuiteTools suite;
    private final CoverLifetimes lifetimes;

    CoverTool(SuiteTools suite, CoverLifetimes lifetimes) {
        this.suite = suite;
        this.lifetimes = lifetimes;
    }

    private static String id(Block b) { return b.getType().getKey().getKey(); }

    private static long key(int cx, int cz) { return (long) cx << 32 | (cz & 0xffffffffL); }

    @Override
    public ToolOutcome run(JsonNode in, Authority authority, String actors) throws GameTools.ToolError, InterruptedException {
        CoverSpec s = CoverSpec.parse(in, authority);
        for (CoverSpec.Weighted b : s.blocks()) {
            Material m = Material.matchMaterial(b.id());
            if (m == null || !m.isBlock() || m.isAir() || m.isLegacy()) throw new GameTools.ToolError(b.id() + " is not a placeable block.");
            SuiteTools.refuse(SuiteGates.block(m.getKey().getKey(), authority));
        }
        Location c = SuiteTools.point(s.at(), suite.base(s.at()));
        World w = c.getWorld();
        int r = s.radius(), cx = c.getBlockX(), cz = c.getBlockZ();
        Map<Long, CoverPlan.Column> columns = new ConcurrentHashMap<>();
        ChunkFanOut<Boolean> reads = new ChunkFanOut<>(suite.plugin(), w);
        for (int chx = cx - r >> 4; chx <= cx + r >> 4; chx++)
            for (int chz = cz - r >> 4; chz <= cz + r >> 4; chz++) {
                int fx = chx, fz = chz;
                reads.submit(fx, fz, () -> { read(w, fx, fz, cx, cz, r, columns); return true; });
            }
        ChunkFanOut.Result<Boolean> read = reads.await(suite.timeoutMs());
        if (read.failed() > 0) throw new GameTools.ToolError("Reading the ground failed: " + read.firstFailure());
        CoverPlan.Result plan = CoverPlan.plan(s, cx, cz, (x, z) -> columns.get(((long) x << 32) ^ (z & 0xffffffffL)), new Random());
        Map<Long, List<CoverPlan.Put>> byChunk = new HashMap<>();
        for (CoverPlan.Put p : plan.puts()) byChunk.computeIfAbsent(key(p.x() >> 4, p.z() >> 4), k -> new ArrayList<>()).add(p);
        List<ChangeLog.Change> journal = new ArrayList<>();
        AtomicInteger unsupported = new AtomicInteger();
        List<CoverLifetimes.Pending> pending = Collections.synchronizedList(new ArrayList<>());
        ChunkFanOut<Boolean> writes = new ChunkFanOut<>(suite.plugin(), w);
        for (var e : byChunk.entrySet()) {
            int chx = (int) (e.getKey() >> 32), chz = (int) (long) e.getKey();
            writes.submit(chx, chz, () -> { place(writes, w, chx, chz, e.getValue(), s.lifetimeSeconds(), journal, pending, unsupported); return true; });
        }
        ChunkFanOut.Result<Boolean> wrote = writes.await(suite.timeoutMs());
        List<ChangeLog.Change> done = List.copyOf(journal);
        lifetimes.addAll(List.copyOf(pending));
        String problem = wrote.failed() > 0 ? "Placing failed in " + wrote.failed() + " chunks: " + wrote.firstFailure() + "."
                : wrote.timedOut() > 0 ? "Placing timed out in " + wrote.timedOut() + " chunks." : null;
        suite.plugin().getLogger().info(CommandRunner.clean("[audit] COVER for " + actors + " at " + w.getName() + " " + cx + " " + c.getBlockY() + " " + cz
                + " radius " + r + ", " + done.size() + " blocks"));
        ObjectNode o = M.createObjectNode().put("placed", done.size()).put("skipped_unloaded_columns", plan.unloaded());
        if (unsupported.get() > 0) o.put("skipped_unsupported", unsupported.get());
        if (plan.capped()) o.put("capped", true);
        if (s.lifetimeSeconds() > 0) o.put("lifetime_seconds", s.lifetimeSeconds());
        if (problem != null) o.put("warning", problem + " Some blocks may be missing.");
        o.put("note", "undo takes it back");
        return new ToolOutcome(o.toString(), done);
    }

    /** Region thread: the top solid block of each disc column in this chunk, and the ids above it. */
    private static void read(World w, int chx, int chz, int cx, int cz, int r, Map<Long, CoverPlan.Column> out) {
        for (int x = chx << 4; x < (chx << 4) + 16; x++)
            for (int z = chz << 4; z < (chz << 4) + 16; z++) {
                if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > r * r) continue;
                int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                while (y > w.getMinHeight() && CoverPlan.FREE.contains(id(w.getBlockAt(x, y, z)))) y--;
                String above = y + 1 < w.getMaxHeight() ? id(w.getBlockAt(x, y + 1, z)) : "stone", above2 = y + 2 < w.getMaxHeight() ? id(w.getBlockAt(x, y + 2, z)) : "stone";
                out.put(((long) x << 32) ^ (z & 0xffffffffL), new CoverPlan.Column(y, id(w.getBlockAt(x, y, z)), above, above2));
            }
    }

    private static boolean free(Block b) { return CoverPlan.FREE.contains(id(b)); }

    /**
     * Region thread: sets the planned blocks that are still free and can stay there, each under the fan-out's lock and journalled as
     * it is made (E1), and records their lifetime (E6).
     */
    private void place(ChunkFanOut<?> fan, World w, int chx, int chz, List<CoverPlan.Put> puts, int lifetime, List<ChangeLog.Change> journal,
                       List<CoverLifetimes.Pending> pending, AtomicInteger unsupported) {
        List<ChangeLog.BlockChange> done = new ArrayList<>();
        List<int[]> touched = new ArrayList<>();
        try {
            for (CoverPlan.Put p : puts) {
                if (BlockSpot.refusalHere(w, p.x(), p.y() + (p.tall() ? 1 : 0), p.z()) != null) continue;
                Block b = w.getBlockAt(p.x(), p.y(), p.z()), up = b.getRelative(0, 1, 0), under = b.getRelative(0, -1, 0);
                if (p.y() + 1 >= w.getMaxHeight() || !free(b) || p.tall() && !free(up) || Graves.block(under) || Graves.block(b) || Graves.block(up)) continue;
                BlockData d = Bukkit.createBlockData(Material.matchMaterial(p.block()));
                if (!d.isSupported(b)) { unsupported.incrementAndGet(); continue; }
                boolean open = fan.open(() -> {
                    set(b, d, p.tall() ? Bisected.Half.BOTTOM : null, done, journal);
                    touched.add(new int[] {p.x(), p.y(), p.z()});
                    if (p.tall()) {
                        set(up, d.clone(), Bisected.Half.TOP, done, journal);
                        touched.add(new int[] {p.x(), p.y() + 1, p.z()});
                    }
                });
                if (!open) break;
            }
            BlockConnections.apply(w, touched);
        } finally {
            if (lifetime > 0 && !done.isEmpty()) {
                pending.add(new CoverLifetimes.Pending(w.getName(), chx, chz, Instant.now().plusSeconds(lifetime), List.copyOf(done)));
                settleLater(w, chx, chz, lifetime * 20L);
            }
        }
    }

    /** journal is shared by every chunk's task; set is called under the fan-out's lock. */
    private static void set(Block b, BlockData d, Bisected.Half half, List<ChangeLog.BlockChange> done, List<ChangeLog.Change> journal) {
        String before = b.getBlockData().getAsString();
        if (half != null && d instanceof Bisected bi) bi.setHalf(half);
        b.setBlockData(d, false);
        ChangeLog.BlockChange c = new ChangeLog.BlockChange(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), before, b.getBlockData().getAsString());
        done.add(c);
        journal.add(c);
    }

    private void settleLater(World w, int chx, int chz, long ticks) {
        Bukkit.getRegionScheduler().runDelayed(suite.plugin(), w, chx, chz, t -> settle(w, chx, chz), Math.max(1, ticks));
    }

    /** Region thread: removes the chunk's expired cover that is still unchanged; an unloaded chunk waits for its next load. */
    private void settle(World w, int chx, int chz) {
        if (!w.isChunkLoaded(chx, chz) || !Bukkit.isOwnedByCurrentRegion(w, chx, chz)) return;
        List<CoverLifetimes.Pending> due = lifetimes.due(w.getName(), chx, chz);
        if (due.isEmpty()) return;
        Bukkit.getAsyncScheduler().runNow(suite.plugin(), t -> lifetimes.save());
        for (CoverLifetimes.Pending p : due)
            for (ChangeLog.BlockChange c : p.blocks()) {
                Block b = w.getBlockAt(c.x(), c.y(), c.z());
                if (b.getBlockData().getAsString().equals(c.after())) b.setBlockData(Bukkit.createBlockData(c.before()), false);
            }
    }

    /** At start: every saved lifetime gets a settle task at its time; one whose chunk is not loaded then waits for the load. */
    void resume() {
        Instant now = Instant.now();
        for (CoverLifetimes.Pending p : lifetimes.all()) {
            World w = Bukkit.getWorld(p.world());
            if (w != null) settleLater(w, p.chunkX(), p.chunkZ(), Math.max(0, java.time.Duration.between(now, p.until()).toMillis() / 50));
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World w = event.getWorld();
        int chx = event.getChunk().getX(), chz = event.getChunk().getZ();
        Instant now = Instant.now();
        for (CoverLifetimes.Pending p : lifetimes.all())
            if (p.world().equals(w.getName()) && p.chunkX() == chx && p.chunkZ() == chz)
                settleLater(w, chx, chz, Math.max(1, java.time.Duration.between(now, p.until()).toMillis() / 50));
    }
}
