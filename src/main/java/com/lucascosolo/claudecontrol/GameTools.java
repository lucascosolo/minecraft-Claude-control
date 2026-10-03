package com.lucascosolo.claudecontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

/** The world-facing tools other than run_commands. Called from the agent worker thread only. */
public final class GameTools {
    /** A tool failure that is reported to Claude as is_error, not thrown out of the turn. */
    public static final class ToolError extends Exception {
        public ToolError(String message) { super(message); }
    }

    public record Pos(int x, int y, int z) {}

    /** placed is the number of blocks set; error is null on success. */
    public record OpResult(String block, int placed, String error) {}

    private static final int MAX_CHAT = 1000;
    private final Plugin plugin;
    private final LongSupplier timeoutMs;
    private final Logger log;
    private volatile String chatPrefix;

    String chatPrefix() { return chatPrefix; }

    public GameTools(Plugin plugin, String chatPrefix, LongSupplier timeoutMs) {
        this.plugin = plugin;
        this.timeoutMs = timeoutMs;
        this.log = plugin.getLogger();
        this.chatPrefix = chatPrefix;
    }

    public void setChatPrefix(String chatPrefix) { this.chatPrefix = chatPrefix; }

    public String sendChat(String message, String playerName) throws ToolError {
        String prefix = chatPrefix;
        String plain = ChatText.plain(message);
        if (plain.isEmpty()) throw new ToolError("The message had no plain text. Write words; emoji are removed because Bedrock players cannot see them.");
        String capped = plain.length() > MAX_CHAT ? plain.substring(0, MAX_CHAT) : plain;
        Component text = Component.text(capped.lines().map(l -> prefix + l).collect(Collectors.joining("\n")));
        if (playerName == null) {
            // Each player gets the line on their own scheduler: a server-wide broadcast did not reach Bedrock players.
            int sent = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getScheduler().run(plugin, t -> p.sendMessage(text), null) != null) sent++;
            }
            log.info(CommandRunner.clean("[chat to " + sent + " player(s)] " + prefix + capped));
            return sent == 0 ? "Nobody is online to read it." : "Message sent to " + sent + " player(s).";
        }
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null) throw new ToolError("Player " + playerName + " is not online.");
        if (p.getScheduler().run(plugin, t -> p.sendMessage(text), null) == null) {
            throw new ToolError("Player " + playerName + " is not online.");
        }
        log.info(CommandRunner.clean("[whisper to " + playerName + "] " + capped));
        return "Message sent to " + playerName + ".";
    }

    public String surveyArea(String worldName, int x, int z, int radius, int footprint) throws ToolError, InterruptedException {
        if (radius < 1 || radius > 12) throw new ToolError("radius must be 1..12.");
        if (footprint < 3 || footprint > 15) throw new ToolError("footprint must be 3..15.");
        World world = world(worldName);
        return onRegion(world, x, z, "Survey", () -> {
            Columns c = collect(world, x, z, radius);
            var sites = SiteFinder.find(c.originX(), c.originZ(), c.height(), c.liquid(), footprint, 3, x, z);
            return SurveyRenderer.render(world.getName(), c.originX(), c.originZ(), c.height(), c.surface(), sites, c.entities());
        });
    }

    /**
     * The one column scan behind survey_area, build auto-siting and the world report. Must run on the region that owns
     * (cx, cz); columns in chunks that are not loaded or not owned by it are unknown and count as liquid.
     */
    static Columns collect(World world, int cx, int cz, int radius) {
        int side = 2 * radius + 1, originX = cx - radius, originZ = cz - radius;
        int[][] height = new int[side][side];
        boolean[][] liquid = new boolean[side][side];
        String[][] surface = new String[side][side];
        Map<String, Integer> entities = new TreeMap<>();
        Set<Long> seen = new HashSet<>();
        for (int zi = 0; zi < side; zi++) {
            for (int xi = 0; xi < side; xi++) {
                int x = originX + xi, z = originZ + zi;
                height[zi][xi] = Columns.UNKNOWN;
                liquid[zi][xi] = true;
                if (BlockSpot.refusalHere(world, x, world.getMinHeight(), z) != null) continue;
                if (seen.add((long) (x >> 4) << 32 | (z >> 4) & 0xffffffffL)) {
                    for (Entity e : world.getChunkAt(x >> 4, z >> 4).getEntities()) {
                        var l = e.getLocation();
                        if (!(e instanceof Player) && l.getBlockX() >= originX && l.getBlockX() < originX + side
                                && l.getBlockZ() >= originZ && l.getBlockZ() < originZ + side) {
                            entities.merge(e.getType().getKey().getKey(), 1, Integer::sum);
                        }
                    }
                }
                int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block top = world.getBlockAt(x, y, z);
                height[zi][xi] = y;
                liquid[zi][xi] = top.isLiquid();
                surface[zi][xi] = top.getType().getKey().getKey();
            }
        }
        return new Columns(originX, originZ, height, surface, liquid, entities);
    }

    /** Best free site of the given size within 12 of (px, pz), with the build blocking rules. */
    SiteFinder.Site autoSite(World world, int px, int pz, int size) throws ToolError, InterruptedException {
        List<SiteFinder.Site> sites = onRegion(world, px, pz, "Site search", () -> {
            Columns c = collect(world, px, pz, 12);
            return SiteFinder.find(c.originX(), c.originZ(), c.height(), c.siteBlocked(px, pz), size, 1, px, pz);
        });
        if (sites.isEmpty()) throw new ToolError("no suitable site nearby");
        return sites.getFirst();
    }

    /** What a build placed, and whether it stopped waiting before the placing task finished (changes holds everything made either way). */
    record Placed(List<OpResult> results, boolean timedOut) {}

    /**
     * Places the plan with its minimum corner at origin, in one task on the region that owns the origin. Every change is made
     * through the fan-out's lock and journalled as it is made, so after a timeout nothing more is changed and changes is complete.
     */
    Placed place(World world, Pos origin, BuildPlan plan, boolean clear, List<ChangeLog.Change> changes) throws ToolError, InterruptedException {
        if (origin.y() < world.getMinHeight() || origin.y() + plan.height() > world.getMaxHeight()) {
            throw new ToolError("the box is outside the world's height range " + world.getMinHeight() + ".." + (world.getMaxHeight() - 1) + ".");
        }
        if (!world.isChunkLoaded(origin.x() >> 4, origin.z() >> 4)) throw new ToolError("Build refused: the chunk at " + origin.x() + " " + origin.z()
                + " is not loaded (no player is near); tools never load or generate chunks.");
        ChunkFanOut<List<OpResult>> fan = new ChunkFanOut<>(plugin, world);
        fan.submit(origin.x() >> 4, origin.z() >> 4, () -> placeHere(fan, world, origin, plan, clear, changes));
        ChunkFanOut.Result<List<OpResult>> r = fan.await(timeoutMs.getAsLong());
        if (r.failed() > 0) {
            if (r.firstFailure() instanceof ToolError inner) throw inner;
            throw new ToolError("Build failed: " + r.firstFailure());
        }
        if (r.timedOut() > 0) return new Placed(List.of(), true);
        if (r.notLoaded() > 0) throw new ToolError("Build refused: the chunk at " + origin.x() + " " + origin.z() + " is no longer loaded; tools never load or generate chunks.");
        return new Placed(r.values().getFirst(), false);
    }

    private List<OpResult> placeHere(ChunkFanOut<?> fan, World world, Pos origin, BuildPlan plan, boolean clear, List<ChangeLog.Change> changes) throws ToolError {
        for (int cx = origin.x() - 1 >> 4; cx <= origin.x() + plan.width() >> 4; cx++) {
            for (int cz = origin.z() - 1 >> 4; cz <= origin.z() + plan.depth() >> 4; cz++) {
                if (BlockSpot.refusalHere(world, cx << 4, origin.y(), cz << 4) != null) {
                    throw new ToolError("site is not in a loaded region near the player");
                }
            }
        }
        String grave = Graves.in(world, origin.x() - 1, origin.y() - 1, origin.z() - 1,
                origin.x() + plan.width(), origin.y() + plan.height(), origin.z() + plan.depth());
        if (grave != null) throw new ToolError("refused: a grave (or player head) is at " + grave + ", next to or inside this box. Graves are never "
                + "touched; build somewhere else or leave a gap around it.");
        List<OpResult> out = new ArrayList<>();
        List<int[]> touched = new ArrayList<>();
        if (clear) {
            BlockData air = Bukkit.createBlockData(Material.AIR);
            for (int y = 0; y < plan.height(); y++) for (int z = 0; z < plan.depth(); z++) for (int x = 0; x < plan.width(); x++) {
                Block b = world.getBlockAt(origin.x() + x, origin.y() + y, origin.z() + z);
                if (!fan.open(() -> {
                    if (!b.getType().isAir()) changes.add(new ChangeLog.BlockChange(world.getName(), b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString(), "minecraft:air"));
                    b.setBlockData(air, false);
                })) return out;
                if (x == 0 || y == 0 || z == 0 || x == plan.width() - 1 || y == plan.height() - 1 || z == plan.depth() - 1) {
                    touched.add(new int[] {b.getX(), b.getY(), b.getZ()});
                }
            }
        }
        for (BuildPlan.Op op : plan.ops()) {
            BlockData data;
            try {
                data = Bukkit.createBlockData(op.block());
            } catch (IllegalArgumentException e) {
                out.add(new OpResult(op.block(), 0, "unknown block or state: " + op.block()));
                continue;
            }
            List<int[]> cells = plan.positions(op);
            for (int[] p : cells) {
                Block b = world.getBlockAt(origin.x() + p[0], origin.y() + p[1], origin.z() + p[2]);
                if (!fan.open(() -> {
                    changes.add(new ChangeLog.BlockChange(world.getName(), b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString(), data.getAsString()));
                    b.setBlockData(data, false);
                })) return out;
                touched.add(new int[] {b.getX(), b.getY(), b.getZ()});
            }
            out.add(new OpResult(op.block(), cells.size(), null));
        }
        fan.open(() -> {
            BlockConnections.apply(world, touched);
            for (int[] p : touched) ContainerTool.stamp(world.getBlockAt(p[0], p[1], p[2]), plugin);
        });
        return out;
    }

    /** The build tool: resolve the site, place, then teleport and announce through the normal paths. */
    public ToolOutcome build(String actors, String playerName, int width, int depth, int height, Pos origin, boolean clear,
                        List<BuildPlan.Op> ops, int[] teleportTo, String message) throws ToolError, InterruptedException {
        return build(actors, playerName, width, depth, height, origin, clear, ops, teleportTo, message, Authority.NONE);
    }

    public ToolOutcome build(String actors, String playerName, int width, int depth, int height, Pos origin, boolean clear,
                        List<BuildPlan.Op> ops, int[] teleportTo, String message, Authority authority) throws ToolError, InterruptedException {
        BuildPlan plan;
        try {
            plan = BuildPlan.parse(width, depth, height, ops, clear);
        } catch (BuildPlan.InvalidPlan e) {
            throw new ToolError(e.getMessage());
        }
        if (teleportTo != null && !plan.contains(teleportTo)) throw new ToolError("teleport_to must be [x,y,z] inside the box.");
        Player p = online(playerName);
        Location at = read(p, p::getLocation);
        World world = at.getWorld();
        if (origin == null) {
            SiteFinder.Site site = autoSite(world, at.getBlockX(), at.getBlockZ(), Math.max(width, depth));
            origin = new Pos(site.minX(), site.floorY(), site.minZ());
        }
        log.info(CommandRunner.clean("[audit] BUILD for " + actors + " near " + playerName + " at " + world.getName() + " "
                + origin.x() + " " + origin.y() + " " + origin.z() + ", size " + width + "x" + depth + "x" + height + ", " + ops.size() + " ops"));
        for (BuildPlan.Op op : ops) {
            if (op.block() == null) continue;
            if (SuiteGates.grave(op.block())) throw new ToolError("refused: heads and skulls cannot be placed with build (" + op.block() + ").");
            Material m = Material.matchMaterial(op.block().replaceFirst("\\[.*", ""));
            String key = m == null ? null : m.getKey().getKey();
            String refusal = SuiteGates.hazard(op.block(), authority);
            if (refusal == null && key != null) refusal = SuiteGates.hazard(key, authority);
            if (key != null && SuiteGates.grave(key)) refusal = "heads and skulls cannot be placed with build (" + key + ").";
            if (refusal != null) throw new ToolError("refused: " + refusal);
        }
        List<ChangeLog.Change> changes = Collections.synchronizedList(new ArrayList<>());
        Placed placed = place(world, origin, plan, clear, changes);
        if (placed.timedOut()) {
            List<ChangeLog.Change> made = List.copyOf(changes);
            return new ToolOutcome("Build timed out and was cancelled. " + made.size() + " block changes were made and recorded; undo takes them back.", made);
        }
        List<OpResult> results = placed.results();
        boolean teleported = false, sent = false;
        if (teleportTo != null) {
            try {
                teleportPlayer(playerName, world.getName(), origin.x() + teleportTo[0], origin.y() + teleportTo[1], origin.z() + teleportTo[2]);
                teleported = true;
            } catch (ToolError e) {
                log.info("Build teleport failed: " + e.getMessage());
            }
        }
        if (message != null && results.stream().allMatch(r -> r.error() == null)) {
            try {
                sendChat(message, null);
                sent = true;
            } catch (ToolError e) {
                log.info("Build message not sent: " + e.getMessage());
            }
        }
        return new ToolOutcome(ToolJson.build(origin, width, depth, height, results, teleported, sent, world.getName()), changes);
    }

    /**
     * B4, the one safe-destination routine (teleport_player, places go, build): on the destination's region, the spot or the
     * nearest one within 8 blocks with solid harmless ground, two air blocks and no liquid; never loads a chunk.
     */
    public String teleportPlayer(String playerName, String worldName, int tx, int ty, int tz) throws ToolError, InterruptedException {
        Player p = online(playerName);
        World world = world(worldName);
        if (BlockSpot.refusal(world, tx, ty, tz) != null) throw new ToolError("Teleport refused: " + BlockSpot.refusal(world, tx, ty, tz));
        int[] spot = onRegion(world, tx, tz, "Teleport check", () -> SafeSpot.find((x, y, z) -> BlockSpot.kind(world, x, y, z), tx, ty, tz));
        if (spot == null) throw new ToolError("Teleport refused: no safe spot (solid ground, two air blocks, no liquid) within 8 blocks of "
                + world.getName() + " " + tx + " " + ty + " " + tz + ".");
        int x = spot[0], y = spot[1], z = spot[2];
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        var task = p.getScheduler().run(plugin, t -> p.teleportAsync(new Location(world, x + 0.5, y, z + 0.5))
                .whenComplete((ok, err) -> { if (err != null) done.completeExceptionally(err); else done.complete(ok); }),
                () -> done.complete(false));
        if (task == null) throw new ToolError("Player " + playerName + " is not online.");
        String where = world.getName() + " " + x + " " + y + " " + z;
        if (!await(done, "Teleport")) throw new ToolError("Teleport of " + playerName + " to " + where + " failed.");
        return "Teleported " + playerName + " to " + where + (x == tx && y == ty && z == tz ? "." : " (the nearest safe spot to " + tx + " " + ty + " " + tz + ").");
    }

    private <T> T await(CompletableFuture<T> f, String what) throws ToolError, InterruptedException {
        try {
            return f.get(timeoutMs.getAsLong(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ToolError(what + " timed out.");
        } catch (ExecutionException e) {
            throw new ToolError(what + " failed: " + e.getCause());
        }
    }

    /** An effect for the entities tool. */
    public record Effect(String type, int seconds, int amplifier) {}

    /**
     * Lists, removes or applies an effect to entities within radius of a player or a block, on the region thread that owns the
     * centre. Entities owned by another region are skipped and counted; players are never targeted.
     */
    public String entities(String nearPlayer, String worldName, int x, int y, int z, int radius, List<String> types, String action,
                           Effect effect, int limit, boolean includeNamed) throws ToolError, InterruptedException {
        return entitiesOutcome(nearPlayer, worldName, x, y, z, radius, types, action, effect, limit, includeNamed).text();
    }

    /** entities, plus an EntityRemoved change for every entity removed. */
    ToolOutcome entitiesOutcome(String nearPlayer, String worldName, int x, int y, int z, int radius, List<String> types, String action,
                           Effect effect, int limit, boolean includeNamed) throws ToolError, InterruptedException {
        if (radius < 1 || radius > 64) throw new ToolError("radius must be 1..64.");
        if (limit < 1 || limit > 200) throw new ToolError("limit must be 1..200.");
        if (!List.of("list", "remove", "effect").contains(action)) throw new ToolError("action must be list, remove or effect.");
        EntityFilter filter;
        try {
            filter = EntityFilter.parse(types);
        } catch (IllegalArgumentException e) {
            throw new ToolError(e.getMessage());
        }
        org.bukkit.potion.PotionEffectType effectType = null;
        if (action.equals("effect")) {
            if (effect == null) throw new ToolError("action effect needs effect {type, seconds, amplifier}.");
            effectType = org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(effect.type().toLowerCase().replaceFirst("^minecraft:", "")));
            if (effectType == null) throw new ToolError("unknown effect " + effect.type() + ".");
            if (effect.seconds() < 1 || effect.seconds() > 600 || effect.amplifier() < 0 || effect.amplifier() > 9) {
                throw new ToolError("effect seconds must be 1..600 and amplifier 0..9.");
            }
        }
        Location centre;
        if (nearPlayer != null) {
            Player p = online(nearPlayer);
            centre = read(p, p::getLocation);
        } else {
            centre = new Location(world(worldName), x + 0.5, y, z + 0.5);
        }
        org.bukkit.potion.PotionEffectType type = effectType;
        String where = "within " + radius + " of " + (nearPlayer != null ? nearPlayer : centre.getWorld().getName() + " " + x + "," + y + "," + z);
        List<ChangeLog.Change> removed = new java.util.ArrayList<>();
        String text = onRegion(centre.getWorld(), centre.getBlockX(), centre.getBlockZ(), "entities", () -> {
            Map<String, Integer> done = new TreeMap<>();
            int otherRegion = 0, protectedSkipped = 0, overLimit = 0, count = 0;
            double r2 = (double) radius * radius;
            for (Entity e : centre.getWorld().getNearbyEntities(centre, radius, radius, radius)) {
                if (!Bukkit.isOwnedByCurrentRegion(e)) { otherRegion++; continue; }
                if (e.getLocation().distanceSquared(centre) > r2 || Graves.entity(e)) continue;
                String id = e.getType().getKey().getKey();
                if (!filter.matches(new EntityFilter.Facts(id, e instanceof Player, e instanceof org.bukkit.entity.Enemy,
                        e instanceof org.bukkit.entity.Animals, e instanceof org.bukkit.entity.Item, e instanceof org.bukkit.entity.Mob))) continue;
                if (action.equals("remove") && !includeNamed && (e.customName() != null
                        || e instanceof org.bukkit.entity.Tameable t && t.isTamed())) { protectedSkipped++; continue; }
                if (action.equals("effect") && !(e instanceof org.bukkit.entity.LivingEntity)) continue;
                if (count == limit) { overLimit++; continue; }
                count++;
                switch (action) {
                    case "remove" -> {
                        removed.add(new ChangeLog.EntityRemoved(centre.getWorld().getName(), e.getUniqueId(), id));
                        e.remove();
                    }
                    case "effect" -> ((org.bukkit.entity.LivingEntity) e).addPotionEffect(new PotionEffect(type, effect.seconds() * 20, effect.amplifier()));
                    default -> {}
                }
                done.merge(id, 1, Integer::sum);
            }
            String verb = switch (action) { case "remove" -> "removed"; case "effect" -> "gave " + effect.type() + " to"; default -> "found"; };
            StringBuilder sb = new StringBuilder(verb + " " + count + " " + where + (done.isEmpty() ? "" : ": " + done.entrySet().stream()
                    .map(en -> en.getKey() + " " + en.getValue()).collect(Collectors.joining(", "))) + ".");
            if (protectedSkipped > 0) sb.append(" Skipped ").append(protectedSkipped).append(" named or tamed (include_named to override).");
            if (overLimit > 0) sb.append(" ").append(overLimit).append(" more matched beyond the limit.");
            if (otherRegion > 0) sb.append(" ").append(otherRegion).append(" entities belong to another region and were skipped; call again nearer to them.");
            return sb.toString();
        });
        return new ToolOutcome(text, removed);
    }

    public String playerDetails(String playerName) throws ToolError, InterruptedException {
        Player p = online(playerName);
        return ReportRenderer.details(read(p, () -> state(p)));
    }

    /** Runs a read on the player's own scheduler, waiting at most 2 s. */
    private <T> T read(Player p, Supplier<T> what) throws ToolError, InterruptedException {
        try {
            return onEntity(plugin, p, what).get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ToolError("Timed out reading " + p.getName() + ".");
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolError("Player " + p.getName() + " is unavailable (" + e.getClass().getSimpleName() + ").");
        }
    }

    static <T> CompletableFuture<T> onEntity(Plugin plugin, Entity e, Supplier<T> read) {
        CompletableFuture<T> f = new CompletableFuture<>();
        var task = e.getScheduler().run(plugin, t -> {
            try {
                f.complete(read.get());
            } catch (Throwable ex) {
                f.completeExceptionally(ex);
            }
        }, () -> f.cancel(false));
        if (task == null) f.cancel(false);
        return f;
    }

    @FunctionalInterface
    interface RegionWork<T> {
        T run() throws ToolError;
    }

    /** Runs work on the region that owns block column (x, z) and waits for it; a ToolError inside is rethrown as is. */
    private <T> T onRegion(World world, int x, int z, String what, RegionWork<T> work) throws ToolError, InterruptedException {
        return region(plugin, world, x, z, what, timeoutMs.getAsLong(), work);
    }

    /**
     * Runs work on the region that owns block column (x, z) and waits for it; a ToolError inside is rethrown as is. A chunk that is
     * not loaded is refused before scheduling (B3: scheduling there would load it). A wait that times out cancels the task;
     * its callers journal changes as they are made.
     */
    static <T> T region(Plugin plugin, World world, int x, int z, String what, long timeoutMs, RegionWork<T> work) throws ToolError, InterruptedException {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) throw new ToolError(what + " refused: the chunk at " + x + " " + z
                + " is not loaded (no player is near); tools never load or generate chunks.");
        ChunkFanOut<T> fan = new ChunkFanOut<>(plugin, world);
        fan.submit(x >> 4, z >> 4, work::run);
        ChunkFanOut.Result<T> r = fan.await(timeoutMs);
        if (r.failed() > 0) {
            if (r.firstFailure() instanceof ToolError inner) throw inner;
            throw new ToolError(what + " failed: " + r.firstFailure());
        }
        if (r.timedOut() > 0) throw new ToolError(what + " timed out; it was cancelled; work that had already run is not undone.");
        if (r.notLoaded() > 0) throw new ToolError(what + " refused: the chunk at " + x + " " + z + " is no longer loaded; tools never load or generate chunks.");
        return r.values().isEmpty() ? null : r.values().getFirst();
    }

    private Player online(String name) throws ToolError {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) throw new ToolError("Player " + name + " is not online.");
        return p;
    }

    private static World world(String name) throws ToolError {
        World w = Bukkit.getWorld(name);
        if (w == null) throw new ToolError("unknown world");
        return w;
    }

    static ReportRenderer.PlayerState state(Player p) {
        var l = p.getLocation();
        Map<String, Integer> inv = new TreeMap<>();
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) inv.merge(it.getType().name().toLowerCase(), it.getAmount(), Integer::sum);
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        List<String> effects = p.getActivePotionEffects().stream()
                .map((PotionEffect e) -> e.getType().getKey().getKey() + " " + (e.getAmplifier() + 1) + " (" + e.getDuration() / 20 + "s)")
                .toList();
        return new ReportRenderer.PlayerState(p.getName(), p.isOp(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(),
                p.getGameMode().name().toLowerCase(), Math.round(p.getHealth()), Math.round(p.getAttribute(Attribute.MAX_HEALTH).getValue()),
                p.getFoodLevel(), p.getLevel(), held.getType().isAir() ? "nothing" : held.getType().name().toLowerCase() + " x" + held.getAmount(),
                inv, effects);
    }

    /** A4: the first grave within radius of any of the points, as "x y z", checked on each point's region; null when none. */
    String graveNear(List<int[]> points, int radius) throws ToolError, InterruptedException {
        World w = Bukkit.getWorlds().getFirst();
        for (int[] p : points) {
            String g = onRegion(w, p[0], p[2], "Grave check", () -> {
                for (int cx = p[0] - radius >> 4; cx <= p[0] + radius >> 4; cx++)
                    for (int cz = p[2] - radius >> 4; cz <= p[2] + radius >> 4; cz++)
                        if (!w.isChunkLoaded(cx, cz)) continue;
                        else if (!Bukkit.isOwnedByCurrentRegion(w, cx, cz)) return "near " + p[0] + " " + p[1] + " " + p[2] + " (could not be checked)";
                return Graves.in(w, p[0] - radius, p[1] - radius, p[2] - radius, p[0] + radius, p[1] + radius, p[2] + radius);
            });
            if (g != null) return g;
        }
        return null;
    }
}
