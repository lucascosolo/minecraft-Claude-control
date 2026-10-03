package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** E6: cover blocks waiting for their lifetime to end, per chunk, saved so a restart or an unloaded chunk does not keep them forever. */
final class CoverLifetimes {
    record Pending(String world, int chunkX, int chunkZ, Instant until, List<ChangeLog.BlockChange> blocks) {}

    private final Clock clock;
    private final JsonFileStore store;
    private final List<Pending> entries = new ArrayList<>();

    CoverLifetimes(Clock clock, Path file) {
        this.clock = clock;
        store = new JsonFileStore(file, clock);
        entries.addAll(JsonFileStore.entries(store.read().path("entries"), CoverLifetimes::parse));
    }

    synchronized void add(Pending p) {
        addAll(List.of(p));
    }

    synchronized void addAll(List<Pending> ps) {
        if (ps.isEmpty()) return;
        entries.addAll(ps);
        save();
    }

    /** Removes and returns the chunk's entries whose time has come; it never writes (region threads), so the caller saves off-thread. */
    synchronized List<Pending> due(String world, int chunkX, int chunkZ) {
        Instant now = clock.instant();
        List<Pending> out = entries.stream()
                .filter(p -> p.world().equals(world) && p.chunkX() == chunkX && p.chunkZ() == chunkZ && !p.until().isAfter(now)).toList();
        entries.removeAll(out);
        return out;
    }

    synchronized List<Pending> all() { return List.copyOf(entries); }

    private static Pending parse(JsonNode n) {
        List<ChangeLog.BlockChange> blocks = new ArrayList<>();
        for (JsonNode b : n.path("blocks"))
            blocks.add(new ChangeLog.BlockChange(n.get("world").asText(), b.get("x").asInt(), b.get("y").asInt(), b.get("z").asInt(),
                    b.get("before").asText(), b.get("after").asText()));
        return new Pending(n.get("world").asText(), n.get("chunkX").asInt(), n.get("chunkZ").asInt(), Instant.parse(n.get("until").asText()),
                List.copyOf(blocks));
    }

    synchronized void save() {
        ObjectNode root = JsonFileStore.emptyObject();
        ArrayNode arr = root.putArray("entries");
        for (Pending p : entries) {
            ObjectNode o = arr.addObject().put("world", p.world()).put("chunkX", p.chunkX()).put("chunkZ", p.chunkZ()).put("until", p.until().toString());
            ArrayNode bs = o.putArray("blocks");
            for (ChangeLog.BlockChange b : p.blocks())
                bs.addObject().put("x", b.x()).put("y", b.y()).put("z", b.z()).put("before", b.before()).put("after", b.after());
        }
        store.write(root);
    }
}
