package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/** undo: lists the last 20 changes, or puts one back where nothing has changed since. Undo is not itself recorded. */
final class UndoTool implements SuiteTool {
    private static final ObjectMapper M = new ObjectMapper();
    private final SuiteTools suite;
    private final ChangeLog log;

    UndoTool(SuiteTools suite, ChangeLog log) {
        this.suite = suite;
        this.log = log;
    }

    @Override
    public ToolOutcome run(JsonNode in, Authority a, String actors) throws GameTools.ToolError, InterruptedException {
        String action = in.path("action").asText("");
        if (action.equals("list")) return ToolOutcome.of(UndoPlan.describeList(log.recent(20), Instant.now(), log::status));
        if (!action.equals("revert")) throw new GameTools.ToolError("action must be list or revert.");
        if (!in.path("id").canConvertToLong()) throw new GameTools.ToolError("revert needs the id of a change (see action list).");
        ChangeLog.Entry entry;
        try {
            entry = log.get(in.path("id").asLong());
        } catch (IllegalArgumentException e) {
            throw new GameTools.ToolError(e.getMessage());
        }
        Set<Integer> already = log.done(entry.seq());
        Map<ChangeLog.Change, Integer> index = new IdentityHashMap<>();
        Map<String, List<ChangeLog.Change>> chunks = new LinkedHashMap<>();
        List<ChangeLog.EntityAdded> entities = new ArrayList<>();
        List<ChangeLog.EntityTraits> traitChanges = new ArrayList<>();
        Set<Integer> settled = new HashSet<>();
        List<String> skipped = new ArrayList<>(), perChunk = new ArrayList<>();
        int[] done = new int[4];
        int skippedTotal = 0, retry = 0;
        for (int i = 0; i < entry.changes().size(); i++) {
            ChangeLog.Change c = entry.changes().get(i);
            if (already.contains(i)) continue;
            index.put(c, i);
            switch (c) {
                case ChangeLog.EntityAdded e -> entities.add(e);
                case ChangeLog.EntityTraits e -> traitChanges.add(e);
                case ChangeLog.EntityRemoved e -> settled.add(i);
                case ChangeLog.ItemsGiven g -> settled.add(i);
                default -> {
                    int[] p = pos(c);
                    chunks.computeIfAbsent(world(c) + " " + (p[0] >> 4) + " " + (p[2] >> 4), k -> new ArrayList<>()).add(c);
                }
            }
        }
        for (Map.Entry<String, List<ChangeLog.Change>> ch : chunks.entrySet()) {
            List<ChangeLog.Change> cs = ch.getValue();
            World w = Bukkit.getWorld(world(cs.get(0)));
            int[] p = pos(cs.get(0));
            String why = w == null ? "world not loaded" : BlockSpot.refusal(w, p[0], p[1], p[2]) != null ? "chunk not loaded" : null;
            UndoPlan.Plan plan = null;
            if (why == null) {
                try {
                    plan = suite.onBlock(new Location(w, p[0], p[1], p[2]), "undo", () -> restore(w, cs));
                } catch (GameTools.ToolError e) {
                    why = e.getMessage();
                }
            }
            if (plan == null) {
                retry += cs.size();
                skippedTotal += cs.size();
                perChunk.add("chunk " + ch.getKey() + ": nothing done (" + why + "); revert again later for these " + cs.size());
                continue;
            }
            for (ChangeLog.Change c : plan.restore()) {
                settled.add(index.get(c));
                done[c instanceof ChangeLog.BlockChange ? 0 : c instanceof ChangeLog.ContainerChange ? 1 : 2]++;
            }
            for (UndoPlan.Skip s : plan.skipped()) {
                settled.add(index.get(s.change()));
                skippedTotal++;
                if (skipped.size() < 10) skipped.add(describe(s.change()) + ": " + s.reason());
            }
            perChunk.add("chunk " + ch.getKey() + ": restored " + plan.restore().size() + ", skipped " + plan.skipped().size());
        }
        for (ChangeLog.EntityAdded e : entities) {
            String r = removeEntity(e);
            if (r == null) {
                done[3]++;
                settled.add(index.get(e));
            } else {
                skippedTotal++;
                if (r.startsWith("retry: ")) retry++;
                else settled.add(index.get(e));
                if (skipped.size() < 10) skipped.add(e.type() + " " + e.id() + ": " + r.replaceFirst("^retry: ", ""));
            }
        }
        int traitsDone = 0;
        for (ChangeLog.EntityTraits e : traitChanges) {
            String r = onEntity(e.world(), e.id(), e.chunkX(), e.chunkZ(), x -> {
                List<String> changed = suite.traits().restore(x, e.before(), e.after());
                return changed.isEmpty() ? "" : "changed since, left as is: " + String.join(", ", changed);
            });
            if (r == null) {
                traitsDone++;
                settled.add(index.get(e));
            } else {
                skippedTotal++;
                if (r.startsWith("retry: ")) retry++;
                else settled.add(index.get(e));
                if (skipped.size() < 10) skipped.add(e.type() + " " + e.id() + " traits: " + r.replaceFirst("^retry: ", ""));
            }
        }
        log.recordUndo(entry.seq(), settled, retry == 0);
        ObjectNode out = M.createObjectNode();
        out.put("status", retry == 0 ? "undone" : "partly undone; revert " + entry.seq() + " again for the " + retry + " change(s) not reached");
        out.putObject("restored").put("blocks", done[0]).put("containers", done[1]).put("signs", done[2]).put("entities_removed", done[3]).put("traits_restored", traitsDone);
        ArrayNode pc = out.putArray("per_chunk");
        perChunk.stream().limit(10).forEach(pc::add);
        if (perChunk.size() > 10) pc.add("and " + (perChunk.size() - 10) + " more chunks");
        out.put("skipped_total", skippedTotal);
        ArrayNode sk = out.putArray("skipped");
        skipped.forEach(sk::add);
        ArrayNode nr = out.putArray("not_restorable");
        if (entry.changes().stream().anyMatch(c -> c instanceof ChangeLog.ItemsGiven)) nr.add("given items are not taken back");
        if (entry.changes().stream().anyMatch(c -> c instanceof ChangeLog.EntityRemoved)) nr.add("removed mobs are not brought back");
        out.put("note", "an undo cannot itself be undone");
        String text = out.toString();
        return ToolOutcome.of(text.length() <= 2000 ? text : text.substring(0, 1997) + "...");
    }

    /** C7: null when removed; "retry: why" when its chunk cannot be reached now; otherwise why it was not removed. */
    private String removeEntity(ChangeLog.EntityAdded e) throws InterruptedException {
        return onEntity(e.world(), e.id(), e.chunkX(), e.chunkZ(), this::removeUnlessKept);
    }

    /** Runs work on the entity's own thread, found by id or near the chunk it was in: null when work returned "", "retry: why" when unreachable now. */
    private String onEntity(String world, UUID id, int chunkX, int chunkZ, java.util.function.Function<Entity, String> work) throws InterruptedException {
        Entity ent = find(id);
        if (ent != null) {
            CompletableFuture<String> f = new CompletableFuture<>();
            Runnable gone = () -> f.complete("gone");
            if (ent.getScheduler().run(suite.plugin(), t -> f.complete(work.apply(ent)), gone) != null) {
                try {
                    String r = suite.await(f, "undo");
                    if (!r.equals("gone")) return r.isEmpty() ? null : r;
                } catch (GameTools.ToolError x) {
                    return "retry: " + x.getMessage();
                }
            }
        }
        if (chunkX == Integer.MIN_VALUE) return "gone (or spawned before undo recorded where)";
        World w = Bukkit.getWorld(world);
        if (w == null || !w.isChunkLoaded(chunkX, chunkZ)) return "retry: its chunk is not loaded";
        CompletableFuture<String> f = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(suite.plugin(), w, chunkX, chunkZ, () -> {
            try {
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int cx = chunkX + dx, cz = chunkZ + dz;
                        if (!w.isChunkLoaded(cx, cz) || !Bukkit.isOwnedByCurrentRegion(w, cx, cz)) continue;
                        for (Entity x : w.getChunkAt(cx, cz, false).getEntities()) {
                            if (x.getUniqueId().equals(id)) {
                                f.complete(work.apply(x));
                                return;
                            }
                        }
                    }
                f.complete("gone (not near where it was spawned)");
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            String r = suite.await(f, "undo");
            return r.isEmpty() ? null : r;
        } catch (GameTools.ToolError x) {
            return "retry: " + x.getMessage();
        }
    }

    /** On the entity's own thread: "" when removed, else why it was kept. */
    private String removeUnlessKept(Entity x) {
        boolean items = x instanceof org.bukkit.inventory.InventoryHolder ih && ih.getInventory() != null
                && java.util.Arrays.stream(ih.getInventory().getContents()).anyMatch(i -> i != null);
        if (!items && x instanceof org.bukkit.entity.LivingEntity le && le.getEquipment() != null)
            items = UndoPlan.gearChanged(suite.spawnGear(x), SuiteTools.gearOf(le));
        String keep = UndoPlan.keepEntity(SuiteTools.tamed(x, suite.ownOwner(x)),
                x instanceof org.bukkit.entity.LivingEntity le && le.isLeashed(), SuiteTools.renamedByPlayer(x, suite.ownName(x)),
                !x.getPassengers().isEmpty(), items);
        if (keep != null) return keep;
        x.remove();
        return "";
    }

    /** Entities Claude spawned are tracked by SuiteTools; Bukkit.getEntity is not safe off-thread on Folia. */
    private Entity find(UUID id) {
        Entity e = suite.spawned(id);
        return e != null && e.isValid() ? e : null;
    }

    private static String world(ChangeLog.Change c) {
        return switch (c) {
            case ChangeLog.BlockChange b -> b.world();
            case ChangeLog.ContainerChange b -> b.world();
            case ChangeLog.SignChange b -> b.world();
            default -> "";
        };
    }

    private static int[] pos(ChangeLog.Change c) {
        return switch (c) {
            case ChangeLog.BlockChange b -> new int[] {b.x(), b.y(), b.z()};
            case ChangeLog.ContainerChange b -> new int[] {b.x(), b.y(), b.z()};
            case ChangeLog.SignChange b -> new int[] {b.x(), b.y(), b.z()};
            default -> new int[3];
        };
    }

    private static Block at(ChangeLog.Change c) {
        return switch (c) {
            case ChangeLog.BlockChange b -> block(b.world(), b.x(), b.y(), b.z());
            case ChangeLog.ContainerChange b -> block(b.world(), b.x(), b.y(), b.z());
            case ChangeLog.SignChange b -> block(b.world(), b.x(), b.y(), b.z());
            default -> null;
        };
    }

    private static Block block(String world, int x, int y, int z) {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z).getBlock();
    }

    private static String describe(ChangeLog.Change c) {
        int[] p = pos(c);
        return p[0] + " " + p[1] + " " + p[2];
    }

    /** Region thread: reads the current state, plans, applies. */
    private static UndoPlan.Plan restore(World w, List<ChangeLog.Change> cs) {
        Map<String, String> blocks = new LinkedHashMap<>();
        Map<String, List<String>> containers = new LinkedHashMap<>(), signs = new LinkedHashMap<>();
        for (ChangeLog.Change c : cs) {
            Block b = at(c);
            String k = UndoPlan.key(w.getName(), b.getX(), b.getY(), b.getZ());
            if (c instanceof ChangeLog.BlockChange) blocks.put(k, b.getBlockData().getAsString());
            // C6: what is in any container or on any sign at this spot now, judged against what Claude left there.
            var state = b.getState(false);
            if (state instanceof org.bukkit.inventory.BlockInventoryHolder h) containers.put(k, ContainerTool.snapshot(h.getInventory().getContents()));
            if (state instanceof Sign s) {
                List<String> lines = new ArrayList<>();
                for (Side side : Side.values())
                    for (int i = 0; i < 4; i++) {
                        String l = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(s.getSide(side).line(i));
                        if (side == Side.FRONT) lines.add(l);
                        else if (!l.isEmpty()) lines.add("back: " + l);
                    }
                signs.put(k, lines);
            }
        }
        var entry = new ChangeLog.Entry(0, Instant.EPOCH, "undo", "", cs);
        UndoPlan.Plan plan = UndoPlan.plan(entry, new UndoPlan.State(blocks, containers, signs, Set.of()));
        List<ChangeLog.Change> restored = new ArrayList<>();
        List<UndoPlan.Skip> skipped = new ArrayList<>(plan.skipped());
        List<int[]> cells = new ArrayList<>();
        for (ChangeLog.Change c : plan.restore()) {
            Block b = at(c);
            if (Graves.block(b)) { skipped.add(new UndoPlan.Skip(c, "grave")); continue; }
            switch (c) {
                case ChangeLog.BlockChange x -> b.setBlockData(Bukkit.createBlockData(x.before()), false);
                case ChangeLog.ContainerChange x -> {
                    var inv = ((Container) b.getState(false)).getInventory();
                    ItemStack[] items = new ItemStack[x.before().size()];
                    for (int i = 0; i < items.length; i++) items[i] = x.before().get(i) == null ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(x.before().get(i)));
                    inv.setContents(items);
                }
                case ChangeLog.SignChange x -> {
                    Sign s = (Sign) b.getState(false);
                    for (int i = 0; i < 4; i++) s.getSide(Side.FRONT).line(i, Component.text(i < x.before().size() ? x.before().get(i) : ""));
                    s.update(true, false);
                }
                default -> {}
            }
            restored.add(c);
            cells.add(new int[] {b.getX(), b.getY(), b.getZ()});
        }
        BlockConnections.apply(w, cells);
        return new UndoPlan.Plan(restored, skipped, plan.notRestorable());
    }
}
