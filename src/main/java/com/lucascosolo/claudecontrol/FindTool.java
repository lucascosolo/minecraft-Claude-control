package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * find: loaded-chunk scans for blocks, entities, biomes and structures (C4: never loads or generates a chunk, 2 s in total), a player's
 * bed and saved places. Folia's locate calls can generate chunks and run for seconds on a region thread, so they are not used.
 */
final class FindTool implements SuiteTool {
    private static final ObjectMapper M = new ObjectMapper();
    private static final int MAX_MATCHES = 20;
    private static final long SCAN_MS = 2000;
    private final SuiteTools suite;
    private final PlaceBook places;

    FindTool(SuiteTools suite, PlaceBook places) {
        this.suite = suite;
        this.places = places;
    }

    private record Hit(String type, int x, int y, int z, double distance) {}

    @Override
    public ToolOutcome run(JsonNode in, Authority authority, String actors) throws GameTools.ToolError, InterruptedException {
        FindSpec s;
        try {
            s = FindSpec.parse(in);
        } catch (IllegalArgumentException e) {
            throw new GameTools.ToolError(e.getMessage());
        }
        SuiteTools.refuse(FindSpec.refusal(s, authority));
        ObjectNode out = switch (s.what()) {
            case "saved_place" -> savedPlaces(s);
            case "player_bed" -> bed(s);
            default -> searchAround(s);
        };
        String text = out.toString();
        return ToolOutcome.of(text.length() <= SuiteTools.MAX_RESULT ? text : text.substring(0, SuiteTools.MAX_RESULT - 3) + "...");
    }

    private ObjectNode savedPlaces(FindSpec s) {
        ArrayNode l = M.createArrayNode();
        for (PlaceBook.Place p : places.list())
            if (s.id() == null || p.name().toLowerCase(Locale.ROOT).contains(s.id().toLowerCase(Locale.ROOT)))
                l.add(p.name() + ": " + p.world() + " " + p.x() + " " + p.y() + " " + p.z());
        return M.createObjectNode().set("places", l);
    }

    private ObjectNode bed(FindSpec s) throws GameTools.ToolError, InterruptedException {
        if (s.id() == null) throw new GameTools.ToolError("name a player.");
        Player p = Bukkit.getPlayerExact(s.id());
        Location bed;
        if (p != null) bed = suite.await(GameTools.onEntity(suite.plugin(), p, () -> p.getRespawnLocation(false)), "reading " + p.getName());
        else {
            var off = Bukkit.getOfflinePlayerIfCached(s.id());
            if (off == null) throw new GameTools.ToolError("Player " + s.id() + " is not online and not known to this server.");
            bed = off.getRespawnLocation(false);
        }
        ObjectNode o = M.createObjectNode().put("player", s.id());
        if (bed == null || bed.getWorld() == null) return o.put("bed", "none set");
        return o.put("bed", bed.getWorld().getName() + " " + bed.getBlockX() + " " + bed.getBlockY() + " " + bed.getBlockZ());
    }

    private ObjectNode searchAround(FindSpec s) throws GameTools.ToolError, InterruptedException {
        JsonNode near = s.near().isTextual() ? M.createObjectNode().put("near", s.near().asText()) : s.near();
        PlaceExpr expr;
        try {
            expr = PlaceExpr.parse(near);
        } catch (IllegalArgumentException e) {
            throw new GameTools.ToolError(e.getMessage());
        }
        return scan(s, SuiteTools.point(expr, suite.base(expr)));
    }

    private ObjectNode scan(FindSpec s, Location c) throws GameTools.ToolError, InterruptedException {
        String what = s.what();
        boolean blocks = what.equals("block");
        String id = switch (what) {
            case "block" -> SuiteTools.id("block", s.id(), blockIds());
            case "entity" -> SuiteTools.id("entity", s.id(), SuiteTools.entityIds());
            case "biome" -> SuiteTools.id("biome", s.id(), SuiteTools.keys(Registry.BIOME));
            default -> SuiteTools.id("structure", s.id(), SuiteTools.keys(Registry.STRUCTURE));
        };
        Material material = blocks ? Material.matchMaterial(id) : null;
        EntityType type = what.equals("entity") ? EntityType.fromName(id) : null;
        Biome biome = what.equals("biome") ? Registry.BIOME.get(NamespacedKey.minecraft(id)) : null;
        Structure structure = what.equals("structure") ? Registry.STRUCTURE.get(NamespacedKey.minecraft(id)) : null;
        World w = c.getWorld();
        int r = s.radius(), reach = (r + 15) / 16, ccx = c.getBlockX() >> 4, ccz = c.getBlockZ() >> 4;
        long deadline = System.currentTimeMillis() + SCAN_MS;
        ChunkFanOut<List<Hit>> fan = new ChunkFanOut<>(suite.plugin(), w);
        for (int cx = ccx - reach; cx <= ccx + reach; cx++)
            for (int cz = ccz - reach; cz <= ccz + reach; cz++) {
                int fx = cx, fz = cz;
                fan.submit(cx, cz, () -> {
                    var chunk = w.getChunkAt(fx, fz, false);
                    return switch (what) {
                        case "block" -> scanBlocks(chunk.getChunkSnapshot(false, false, false), w, fx, fz, material, c, r, deadline);
                        case "entity" -> scanEntities(chunk.getEntities(), type, c, r);
                        case "biome" -> scanBiome(w, fx, fz, biome, id, c, r);
                        default -> scanStructures(chunk.getStructures(structure), id, c, r);
                    };
                });
            }
        ChunkFanOut.Result<List<Hit>> res = fan.await(deadline - System.currentTimeMillis());
        List<Hit> hits = new ArrayList<>();
        res.values().forEach(hits::addAll);
        int searched = res.values().size(), unloaded = res.notLoaded(), timedOut = res.timedOut(), failed = res.failed();
        hits = new ArrayList<>(new java.util.LinkedHashSet<>(hits));
        hits.sort(Comparator.comparingDouble(Hit::distance));
        ArrayNode l = M.createArrayNode();
        for (Hit h : hits.subList(0, Math.min(MAX_MATCHES, hits.size())))
            l.add(h.type() + " " + h.x() + " " + h.y() + " " + h.z() + " d=" + Math.round(h.distance() * 10) / 10.0);
        ObjectNode o = M.createObjectNode().put("what", s.what()).put("id", id).put("world", w.getName()).put("found", hits.size());
        o.set("nearest", l);
        o.put("chunksSearched", searched).put("chunksNotLoaded", unloaded).put("chunksTimedOut", timedOut).put("chunksFailed", failed);
        if (unloaded + timedOut + failed > 0) o.put("skipped", (unloaded > 0 ? unloaded + " chunks are not loaded and were not searched (find never loads or generates chunks)" : "")
                + (unloaded > 0 && timedOut > 0 ? "; " : "") + (timedOut > 0 ? timedOut + " chunks timed out (not reached within the 2 s budget)" : "")
                + ((unloaded + timedOut > 0 && failed > 0) ? "; " : "") + (failed > 0 ? failed + " chunks failed (an error while searching them)" : ""));
        return o;
    }

    private static List<Hit> scanBlocks(ChunkSnapshot snap, World w, int cx, int cz, Material m, Location c, int r, long deadline) {
        List<Hit> out = new ArrayList<>();
        int y0 = Math.max(w.getMinHeight(), c.getBlockY() - r), y1 = Math.min(w.getMaxHeight() - 1, c.getBlockY() + r);
        for (int y = y0; y <= y1; y++) {
            if (System.currentTimeMillis() > deadline) break;
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) {
                    if (snap.getBlockType(x, y, z) != m) continue;
                    int bx = cx * 16 + x, bz = cz * 16 + z;
                    double d = Math.sqrt(sq(bx + 0.5 - c.getX()) + sq(y + 0.5 - c.getY()) + sq(bz + 0.5 - c.getZ()));
                    if (d <= r) out.add(new Hit(m.getKey().getKey(), bx, y, bz, d));
                }
        }
        out.sort(Comparator.comparingDouble(Hit::distance));
        return out.size() > MAX_MATCHES ? new ArrayList<>(out.subList(0, MAX_MATCHES)) : out;
    }

    /** One hit per chunk: the nearest column sampled every 4 blocks at the search height. */
    private static List<Hit> scanBiome(World w, int cx, int cz, Biome b, String id, Location c, int r) {
        int y = Math.max(w.getMinHeight(), Math.min(w.getMaxHeight() - 1, c.getBlockY()));
        Hit best = null;
        for (int x = 0; x < 16; x += 4)
            for (int z = 0; z < 16; z += 4) {
                if (w.getBiome(cx * 16 + x, y, cz * 16 + z) != b) continue;
                int bx = cx * 16 + x, bz = cz * 16 + z;
                double d = Math.sqrt(sq(bx + 0.5 - c.getX()) + sq(bz + 0.5 - c.getZ()));
                if (d <= r && (best == null || d < best.distance())) best = new Hit(id, bx, y, bz, d);
            }
        return best == null ? List.of() : List.of(best);
    }

    private static List<Hit> scanStructures(java.util.Collection<GeneratedStructure> found, String id, Location c, int r) {
        List<Hit> out = new ArrayList<>();
        for (GeneratedStructure g : found) {
            var box = g.getBoundingBox();
            double d = Math.sqrt(sq(box.getCenterX() - c.getX()) + sq(box.getCenterZ() - c.getZ()));
            if (d <= r) out.add(new Hit(id, (int) box.getCenterX(), (int) box.getMinY(), (int) box.getCenterZ(), d));
        }
        return out;
    }

    private static List<Hit> scanEntities(Entity[] entities, EntityType type, Location c, int r) {
        List<Hit> out = new ArrayList<>();
        for (Entity e : entities) {
            if (e.getType() != type) continue;
            Location l = e.getLocation();
            double d = l.distance(c);
            if (d <= r) out.add(new Hit(type.getKey().getKey(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), d));
        }
        return out;
    }

    private static double sq(double v) { return v * v; }

    private static java.util.Set<String> blockIds() {
        java.util.Set<String> s = new java.util.TreeSet<>();
        for (Material m : Material.values()) if (!m.isLegacy() && m.isBlock() && !m.isAir()) s.add(m.getKey().getKey());
        return s;
    }
}
