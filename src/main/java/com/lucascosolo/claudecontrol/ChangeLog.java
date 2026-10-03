package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A bounded log (optionally saved to a file) of what tools changed, newest kept. */
final class ChangeLog {
    sealed interface Change permits BlockChange, EntityAdded, EntityRemoved, ItemsGiven, ContainerChange, SignChange, EntityTraits {}

    /** A container's contents before and after, one base64 ItemStack per slot (null for empty); half is single, left or right. */
    record ContainerChange(String world, int x, int y, int z, String half, List<String> before, List<String> after) implements Change {}

    /** A sign's front lines before and after an edit. */
    record SignChange(String world, int x, int y, int z, List<String> before, List<String> after) implements Change {}

    record BlockChange(String world, int x, int y, int z, String before, String after) implements Change {}

    /** chunkX/chunkZ: where it was spawned, so undo finds it on that chunk's region after a restart (MIN_VALUE when unknown). */
    record EntityAdded(String world, UUID id, String type, int chunkX, int chunkZ) implements Change {
        EntityAdded(String world, UUID id, String type) { this(world, id, type, Integer.MIN_VALUE, Integer.MIN_VALUE); }
    }

    record EntityRemoved(String world, UUID id, String type) implements Change {}

    /** F4: traits set on an existing entity, with the values before and after as the applier encodes them. */
    record EntityTraits(String world, UUID id, String type, int chunkX, int chunkZ, java.util.Map<String, String> before,
                        java.util.Map<String, String> after) implements Change {}

    record ItemsGiven(String player, String item, int count) implements Change {}

    /** players: who asked in the turn that made it; origin: turn, later, routine, wake, event or unknown (C5). */
    record Entry(long seq, Instant at, String tool, String actors, List<Change> changes, List<String> players, String origin) {
        Entry(long seq, Instant at, String tool, String actors, List<Change> changes) { this(seq, at, tool, actors, changes, List.of(), "unknown"); }
    }

    record Result(boolean truncated, boolean tooLarge, boolean notRecorded) {
        Result(boolean truncated, boolean tooLarge) { this(truncated, tooLarge, false); }
    }

    static final int MAX_ROUTINE_ENTRIES = 4;
    static final String NOT_RECORDED_NOTE = "changes are no longer recorded";
    private static final int ENTRY_OVERHEAD = 200;

    static final int MAX_CHANGES = 4096;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final java.time.Duration SAVE_EVERY = java.time.Duration.ofSeconds(5);
    private static final ObjectMapper M = new ObjectMapper();
    private final int capacity;
    private final Clock clock;
    private final JsonFileStore store;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Set<Long> reverted = new HashSet<>();
    private final java.util.Map<Long, Set<Integer>> partly = new java.util.HashMap<>();
    private final int maxBytes;
    /** E8: running size and run id of each routine entry made since start; entries loaded from the file have neither and never merge. */
    private final java.util.Map<Long, Long> routineBytes = new java.util.HashMap<>();
    private final java.util.Map<Long, String> routineRun = new java.util.HashMap<>();
    private final Set<String> warned = new HashSet<>();
    java.util.function.Consumer<String> warn = m -> java.util.logging.Logger.getLogger("ClaudeControl").warning(m);
    private long seq;
    private Instant lastSave;
    private boolean dirty;

    ChangeLog(int capacity, Clock clock) {
        this(capacity, clock, null);
    }

    ChangeLog(int capacity, Clock clock, Path file) {
        this(capacity, clock, file, MAX_BYTES);
    }

    ChangeLog(int capacity, Clock clock, Path file, int maxBytes) {
        this.maxBytes = maxBytes;
        this.capacity = capacity;
        this.clock = clock;
        this.store = file == null ? null : new JsonFileStore(file, clock);
        if (store != null) load();
    }

    /** Appends an entry keeping its first 4096 changes; true when it had to cut some. */
    synchronized boolean append(String tool, String actors, List<Change> changes) {
        return append(tool, actors, changes, List.of(), "unknown").truncated();
    }

    /**
     * Appends an entry keeping its first 4096 changes. A routine's beats go into one entry per routine (C10). An entry too large to
     * save is not kept and reported as tooLarge.
     */
    synchronized Result append(String tool, String actors, List<Change> changes, List<String> players, String origin) {
        return append(tool, actors, changes, players, origin, actors);
    }

    /** run: the routine run the beat belongs to (E8); a routine's entries merge and are capped per run. */
    synchronized Result append(String tool, String actors, List<Change> changes, List<String> players, String origin, String run) {
        if (changes == null || changes.isEmpty()) return new Result(false, false);
        boolean routine = "routine".equals(origin);
        List<Change> all = new ArrayList<>(changes);
        boolean cut = all.size() > MAX_CHANGES;
        if (cut) all = all.subList(0, MAX_CHANGES);
        Entry merged = null;
        long beat = routine ? bytes(all) : 0;
        if (routine) {
            if (ENTRY_OVERHEAD + beat > maxBytes) return new Result(cut, true);
            List<Entry> same = entries.stream().filter(e -> run.equals(routineRun.get(e.seq())) && !reverted.contains(e.seq())).toList();
            Entry newest = same.isEmpty() ? null : same.get(0);
            Long size = newest == null ? null : routineBytes.get(newest.seq());
            if (size != null && size + beat <= maxBytes
                    && newest.changes().size() + all.size() <= MAX_CHANGES) {
                merged = newest;
                all.addAll(0, merged.changes());
                cut = false;
            } else if (same.size() >= MAX_ROUTINE_ENTRIES) {
                if (warned.add(run)) warn.accept("[audit] changes of " + actors + " are no longer recorded in the undo journal: its entries are full");
                return new Result(false, false, true);
            }
        }
        Entry e = new Entry(merged == null ? ++seq : merged.seq(), merged == null ? clock.instant() : merged.at(), merged == null ? tool : "routine",
                actors, List.copyOf(all), List.copyOf(players), origin);
        if (!routine && store != null && toJson(e).toString().length() > maxBytes) return new Result(cut, true);
        if (routine) {
            routineBytes.put(e.seq(), merged == null ? ENTRY_OVERHEAD + beat : routineBytes.get(merged.seq()) + beat);
            routineRun.put(e.seq(), run);
        }
        if (merged != null) {
            List<Entry> l = new ArrayList<>(entries);
            l.set(l.indexOf(merged), e);
            entries.clear();
            entries.addAll(l);
        } else entries.addFirst(e);
        while (entries.size() > capacity) {
            long gone = entries.removeLast().seq();
            routineBytes.remove(gone);
            routineRun.remove(gone);
        }
        changed();
        return new Result(cut, false);
    }

    private static long bytes(List<Change> l) {
        long n = 0;
        for (Change c : l) n += changeJson(M.createArrayNode(), c).toString().length() + 1;
        return n;
    }

    synchronized List<Entry> recent(int n) {
        return new ArrayList<>(entries.stream().limit(Math.max(0, n)).toList());
    }

    synchronized Entry get(long id) {
        if (reverted.contains(id) && entries.stream().anyMatch(e -> e.seq() == id)) throw new IllegalArgumentException("change " + id + " was already undone.");
        return entries.stream().filter(e -> e.seq() == id).findFirst().orElseThrow(() -> new IllegalArgumentException("no change " + id
                + "; recent ids: " + entries.stream().limit(20).map(e -> String.valueOf(e.seq())).collect(java.util.stream.Collectors.joining(", ")) + "."));
    }

    synchronized void markReverted(long id) {
        Entry e = entries.stream().filter(x -> x.seq() == id).findFirst().orElse(null);
        Set<Integer> all = new HashSet<>();
        if (e != null) for (int i = 0; i < e.changes().size(); i++) all.add(i);
        recordUndo(id, all, true);
    }

    /** C7: the indexes of the entry's changes already put back. */
    synchronized Set<Integer> done(long id) {
        return Set.copyOf(partly.getOrDefault(id, Set.of()));
    }

    /** C7: records what a revert put back; complete marks the entry undone, otherwise it stays revertible for the rest. */
    synchronized void recordUndo(long id, java.util.Collection<Integer> nowDone, boolean complete) {
        partly.computeIfAbsent(id, k -> new java.util.TreeSet<>()).addAll(nowDone);
        if (complete) reverted.add(id);
        changed();
    }

    synchronized String status(long id) {
        if (reverted.contains(id)) return "undone";
        return partly.containsKey(id) ? "partly undone" : "";
    }

    /** C10: saves at most once every 5 s; flushIfDue (a timer) and flush (shutdown) write what is pending. */
    private void changed() {
        dirty = true;
        flushIfDue();
    }

    synchronized void flushIfDue() {
        if (dirty && (lastSave == null || !clock.instant().isBefore(lastSave.plus(SAVE_EVERY)))) save();
    }

    synchronized void flush() {
        if (dirty) save();
    }

    private void save() {
        dirty = false;
        if (store == null) return;
        lastSave = clock.instant();
        List<Entry> list = new ArrayList<>(entries);
        while (true) {
            ObjectNode root = M.createObjectNode();
            root.put("seq", seq);
            ArrayNode ids = root.putArray("reverted");
            for (Entry e : list) if (reverted.contains(e.seq())) ids.add(e.seq());
            ObjectNode part = root.putObject("partly");
            for (Entry e : list) if (partly.containsKey(e.seq())) partly.get(e.seq()).forEach(part.putArray(String.valueOf(e.seq()))::add);
            ArrayNode arr = root.putArray("entries");
            for (Entry e : list) arr.add(toJson(e));
            if (list.isEmpty() || root.toString().length() <= maxBytes) {
                store.write(root);
                return;
            }
            list.remove(list.size() - 1);
            while (entries.size() > list.size()) entries.removeLast();
        }
    }

    private void load() {
        JsonNode root = store.read();
        seq = root.path("seq").asLong(0);
        for (JsonNode r : root.path("reverted")) reverted.add(r.asLong());
        root.path("partly").fields().forEachRemaining(f -> {
            Set<Integer> s = new java.util.TreeSet<>();
            f.getValue().forEach(i -> s.add(i.asInt()));
            if (f.getKey().matches("\\d+")) partly.put(Long.parseLong(f.getKey()), s);
        });
        for (Entry e : JsonFileStore.entries(root.path("entries"), ChangeLog::fromJson)) entries.addLast(e);
        for (Entry e : entries) {
            seq = Math.max(seq, e.seq());
        }
    }

    private static ObjectNode toJson(Entry e) {
        ObjectNode n = M.createObjectNode().put("seq", e.seq()).put("at", e.at().toString()).put("tool", e.tool()).put("actors", e.actors())
                .put("origin", e.origin());
        strings(n.putArray("players"), e.players());
        ArrayNode cs = n.putArray("changes");
        for (Change c : e.changes()) changeJson(cs, c);
        return n;
    }

    private static ArrayNode changeJson(ArrayNode cs, Change c) {
        ObjectNode o = cs.addObject();
            switch (c) {
                case BlockChange b -> o.put("t", "block").put("world", b.world()).put("x", b.x()).put("y", b.y()).put("z", b.z()).put("before", b.before()).put("after", b.after());
                case ContainerChange b -> {
                    o.put("t", "container").put("world", b.world()).put("x", b.x()).put("y", b.y()).put("z", b.z()).put("half", b.half());
                    strings(o.putArray("before"), b.before());
                    strings(o.putArray("after"), b.after());
                }
                case SignChange b -> {
                    o.put("t", "sign").put("world", b.world()).put("x", b.x()).put("y", b.y()).put("z", b.z());
                    strings(o.putArray("before"), b.before());
                    strings(o.putArray("after"), b.after());
                }
                case EntityAdded b -> o.put("t", "added").put("world", b.world()).put("id", b.id().toString()).put("type", b.type())
                        .put("cx", b.chunkX()).put("cz", b.chunkZ());
                case EntityRemoved b -> o.put("t", "removed").put("world", b.world()).put("id", b.id().toString()).put("type", b.type());
                case EntityTraits b -> {
                    o.put("t", "traits").put("world", b.world()).put("id", b.id().toString()).put("type", b.type()).put("cx", b.chunkX()).put("cz", b.chunkZ());
                    ObjectNode be = o.putObject("before"), af = o.putObject("after");
                    b.before().forEach(be::put);
                    b.after().forEach(af::put);
                }
                case ItemsGiven b -> o.put("t", "given").put("player", b.player()).put("item", b.item()).put("count", b.count());
            }
        return cs;
    }

    private static void strings(ArrayNode a, List<String> l) {
        for (String s : l) if (s == null) a.addNull(); else a.add(s);
    }

    private static List<String> strings(JsonNode a) {
        List<String> l = new ArrayList<>();
        for (JsonNode n : a) l.add(n.isNull() ? null : n.asText());
        return l;
    }

    private static java.util.Map<String, String> map(JsonNode o) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        o.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
        return m;
    }

    private static Entry fromJson(JsonNode n) {
        List<Change> cs = new ArrayList<>();
        for (JsonNode o : n.get("changes")) {
            String w = o.path("world").asText();
            int x = o.path("x").asInt(), y = o.path("y").asInt(), z = o.path("z").asInt();
            cs.add(switch (o.get("t").asText()) {
                case "block" -> new BlockChange(w, x, y, z, o.get("before").asText(), o.get("after").asText());
                case "container" -> new ContainerChange(w, x, y, z, o.get("half").asText(), strings(o.get("before")), strings(o.get("after")));
                case "sign" -> new SignChange(w, x, y, z, strings(o.get("before")), strings(o.get("after")));
                case "added" -> new EntityAdded(w, UUID.fromString(o.get("id").asText()), o.get("type").asText(),
                        o.path("cx").asInt(Integer.MIN_VALUE), o.path("cz").asInt(Integer.MIN_VALUE));
                case "removed" -> new EntityRemoved(w, UUID.fromString(o.get("id").asText()), o.get("type").asText());
                case "traits" -> new EntityTraits(w, UUID.fromString(o.get("id").asText()), o.get("type").asText(),
                        o.path("cx").asInt(Integer.MIN_VALUE), o.path("cz").asInt(Integer.MIN_VALUE), map(o.get("before")), map(o.get("after")));
                case "given" -> new ItemsGiven(o.get("player").asText(), o.get("item").asText(), o.get("count").asInt());
                default -> throw new IllegalArgumentException("kind");
            });
        }
        List<String> players = new ArrayList<>();
        n.path("players").forEach(p -> players.add(p.asText()));
        return new Entry(n.get("seq").asLong(), Instant.parse(n.get("at").asText()), n.get("tool").asText(), n.get("actors").asText(), List.copyOf(cs),
                List.copyOf(players), n.path("origin").asText("unknown"));
    }
}
