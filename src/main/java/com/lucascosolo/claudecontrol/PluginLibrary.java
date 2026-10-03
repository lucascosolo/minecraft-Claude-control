package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** The catalogue of Claude-written plugins (at most 50), active or shelved, persisted after every change. */
final class PluginLibrary {
    static final int MAX = 50, MAX_LIFETIME = 86400;

    /** C2: what the folders say. library maps a base name to the sha-256 of its jar in claude-library/. */
    record Folders(Set<String> active, Map<String, String> library, Set<String> held, Set<String> quarantined) {}

    record Entry(String name, String purpose, String usage, boolean changesWorld, boolean cleansUp, Instant writtenAt, String writtenFor,
                 int timesUsed, String lastWatchResult, boolean safeForEvents, String state, String sha256, Instant until, boolean untilRequestDone) {}

    private final Clock clock;
    private final JsonFileStore file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    PluginLibrary(Clock clock, Path path) {
        this.clock = clock;
        file = new JsonFileStore(path, clock);
        for (Entry e : JsonFileStore.entries(file.read().get("plugins"), PluginLibrary::parse)) entries.put(e.name(), e);
    }

    private static Entry parse(JsonNode n) {
        String until = n.path("until").asText("");
        return new Entry(n.get("name").asText(), n.path("purpose").asText(""), n.path("usage").asText(""), n.path("changes_world").asBoolean(),
                n.path("cleans_up").asBoolean(), Instant.parse(n.get("written_at").asText()), n.path("written_for").asText(""),
                n.path("times_used").asInt(), n.path("last_watch_result").asText(""), n.path("safe_for_events").asBoolean(),
                n.path("state").asText("active"), n.hasNonNull("sha256") ? n.get("sha256").asText() : null,
                until.isEmpty() ? null : Instant.parse(until), n.path("until_request_done").asBoolean());
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}+", " ").strip();
    }

    synchronized void add(String name, String purpose, String usage, boolean changesWorld, boolean cleansUp, String writtenFor) {
        String p = clean(purpose), u = clean(usage);
        if (p.length() > 200) throw new IllegalArgumentException("purpose must be at most 200 characters");
        if (u.length() > 500) throw new IllegalArgumentException("usage must be at most 500 characters");
        if (entries.containsKey(name)) throw new IllegalArgumentException(name + " is already in the plugin library");
        if (entries.size() >= MAX) throw new IllegalArgumentException("the plugin library holds at most " + MAX + " plugins; delete or merge one first");
        entries.put(name, new Entry(name, p, u, changesWorld, cleansUp, clock.instant(), clean(writtenFor), 0, "", false, "active", null, null, false));
        save();
    }

    synchronized Optional<Entry> get(String name) { return Optional.ofNullable(entries.get(name)); }

    synchronized List<Entry> list() { return List.copyOf(entries.values()); }

    private Entry need(String name) {
        Entry e = entries.get(name);
        if (e == null) throw new IllegalArgumentException("no plugin " + name + " in the library");
        return e;
    }

    synchronized void shelve(String name, String sha256) {
        Entry e = need(name);
        if (e.state().equals("shelved")) throw new IllegalArgumentException(name + " is already shelved");
        put(e, "shelved", sha256, null, false);
        save();
    }

    private void put(Entry e, String state, String sha256, Instant until, boolean requestDone) {
        entries.put(e.name(), new Entry(e.name(), e.purpose(), e.usage(), e.changesWorld(), e.cleansUp(), e.writtenAt(), e.writtenFor(), e.timesUsed(),
                e.lastWatchResult(), e.safeForEvents(), state, sha256, until, requestDone));
    }

    /** C2: marks a shelve or activate as in progress before any jar moves; the reconcile settles it if the server dies in between. */
    synchronized void begin(String name, String op) {
        if (!op.equals("shelving") && !op.equals("activating")) throw new IllegalArgumentException("unknown operation " + op);
        Entry e = need(name);
        put(e, op, e.sha256(), e.until(), e.untilRequestDone());
        save();
    }

    synchronized Entry activate(String name, String sha256, String until) {
        Entry e = need(name);
        if (!e.state().equals("shelved") && !e.state().equals("activating")) throw new IllegalArgumentException(name + " is already active");
        if (e.sha256() == null || !e.sha256().equals(sha256)) throw new IllegalArgumentException("the stored jar's sha-256 differs from the one recorded when it was shelved");
        Entry a = withLifetime(e, until);
        a = new Entry(name, a.purpose(), a.usage(), a.changesWorld(), a.cleansUp(), a.writtenAt(), a.writtenFor(), a.timesUsed() + 1,
                a.lastWatchResult(), a.safeForEvents(), "active", a.sha256(), a.until(), a.untilRequestDone());
        entries.put(name, a);
        save();
        return a;
    }

    synchronized void setLifetime(String name, String until) {
        Entry e = need(name);
        if (e.state().equals("shelved")) throw new IllegalArgumentException(name + " is not active");
        entries.put(name, withLifetime(e, until));
        save();
    }

    /** Throws IllegalArgumentException unless until is "request_done" or whole seconds in 1..86400. */
    static void checkUntil(String until) {
        if ("request_done".equals(until)) return;
        long s;
        try {
            s = Long.parseLong(until.strip());
        } catch (NumberFormatException x) {
            s = -1;
        }
        if (s < 1 || s > MAX_LIFETIME) throw new IllegalArgumentException("until must be 1..86400 seconds or \"request_done\"");
    }

    /** C3: no until means 24 h, and request_done also ends after 24 h at the latest. */
    private Entry withLifetime(Entry e, String until) {
        if (until != null) checkUntil(until);
        Instant at = clock.instant().plusSeconds(until == null || until.equals("request_done") ? MAX_LIFETIME : Long.parseLong(until.strip()));
        return new Entry(e.name(), e.purpose(), e.usage(), e.changesWorld(), e.cleansUp(), e.writtenAt(), e.writtenFor(), e.timesUsed(),
                e.lastWatchResult(), e.safeForEvents(), e.state(), e.sha256(), at, "request_done".equals(until));
    }

    /** Drops an entry whose install failed, whatever its state. */
    synchronized void discard(String name) {
        if (entries.remove(name) != null) save();
    }

    synchronized List<Entry> expired(Instant now) {
        return entries.values().stream().filter(e -> e.state().equals("active") && e.until() != null && !e.until().isAfter(now)).toList();
    }

    synchronized List<Entry> requestDone() {
        return entries.values().stream().filter(e -> e.state().equals("active") && e.untilRequestDone()).toList();
    }

    synchronized void setSafeForEvents(String name, boolean v) {
        Entry e = need(name);
        if (v && e.changesWorld()) throw new IllegalArgumentException(name + " changes the world, so it is never safe for random events");
        entries.put(name, new Entry(name, e.purpose(), e.usage(), e.changesWorld(), e.cleansUp(), e.writtenAt(), e.writtenFor(), e.timesUsed(),
                e.lastWatchResult(), v, e.state(), e.sha256(), e.until(), e.untilRequestDone()));
        save();
    }

    synchronized void recordWatchResult(String name, String result) {
        Entry e = entries.get(name);
        if (e == null) return;
        String r = clean(result.length() > 200 ? result.substring(0, 200) : result);
        if (r.equals(e.lastWatchResult())) return;
        entries.put(name, new Entry(name, e.purpose(), e.usage(), e.changesWorld(), e.cleansUp(), e.writtenAt(), e.writtenFor(), e.timesUsed(),
                r, e.safeForEvents(), e.state(), e.sha256(), e.until(), e.untilRequestDone()));
        save();
    }

    synchronized void delete(String name) {
        if (!need(name).state().equals("shelved")) throw new IllegalArgumentException(name + " is active; shelve it first");
        entries.remove(name);
        save();
    }

    /** C2: the folders are the truth; fixes every entry that disagrees and returns one line per correction. */
    synchronized List<String> reconcile(Folders f) {
        List<String> fixes = new ArrayList<>();
        Set<String> names = new TreeSet<>(f.active());
        names.addAll(f.library().keySet());
        names.addAll(f.held());
        names.addAll(f.quarantined());
        for (String n : names) {
            if (entries.containsKey(n)) continue;
            if (entries.size() >= MAX) {
                fixes.add(n + ": has a jar but is not in the catalogue, which is full; left out");
                continue;
            }
            boolean on = f.active().contains(n) || f.held().contains(n);
            String sha = on ? null : f.library().containsKey(n) ? f.library().get(n) : "quarantined";
            entries.put(n, new Entry(n, "not recorded", "not recorded", false, false, clock.instant(), "unknown", 0, "", false, on ? "active" : "shelved", sha,
                    on ? clock.instant().plusSeconds(MAX_LIFETIME) : null, false));
            fixes.add(n + ": has a jar but was not in the catalogue; added");
        }
        for (Entry e : List.copyOf(entries.values())) {
            String n = e.name(), was = e.state();
            if (f.active().contains(n) || (f.held().contains(n) && !was.equals("active") && !was.equals("shelved") && !f.library().containsKey(n))) {
                if (was.equals("active")) continue;
                put(e, "active", e.sha256(), e.until() == null ? clock.instant().plusSeconds(MAX_LIFETIME) : e.until(), e.untilRequestDone());
                fixes.add(n + ": jar is " + (f.active().contains(n) ? "in plugins/" : "held") + " but the catalogue said " + was + "; now active");
            } else if (f.library().containsKey(n)) {
                if (was.equals("shelved")) continue;
                put(e, "shelved", f.library().get(n), null, false);
                fixes.add(n + ": jar is in claude-library/ but the catalogue said " + was + "; now shelved");
            } else if (f.held().contains(n)) {
                continue;
            } else if (f.quarantined().contains(n)) {
                if (was.equals("shelved")) continue;
                put(e, "shelved", "quarantined", null, false);
                fixes.add(n + ": jar is only in claude-quarantine/ but the catalogue said " + was + "; now shelved (quarantined)");
            } else if (!was.equals("shelved")) {
                put(e, "shelved", null, null, false);
                fixes.add(n + ": no jar found anywhere but the catalogue said " + was + "; now shelved without a jar");
            }
        }
        if (!fixes.isEmpty()) save();
        return fixes;
    }

    synchronized List<String> catalogue() {
        return entries.values().stream().limit(30).map(e -> e.name() + " — " + e.purpose()).toList();
    }

    private void save() {
        ObjectNode root = JsonFileStore.emptyObject();
        ArrayNode arr = root.putArray("plugins");
        for (Entry e : entries.values()) {
            ObjectNode o = arr.addObject().put("name", e.name()).put("purpose", e.purpose()).put("usage", e.usage())
                    .put("changes_world", e.changesWorld()).put("cleans_up", e.cleansUp()).put("written_at", e.writtenAt().toString())
                    .put("written_for", e.writtenFor()).put("times_used", e.timesUsed()).put("last_watch_result", e.lastWatchResult())
                    .put("safe_for_events", e.safeForEvents()).put("state", e.state()).put("until_request_done", e.untilRequestDone());
            if (e.sha256() != null) o.put("sha256", e.sha256());
            if (e.until() != null) o.put("until", e.until().toString());
        }
        file.write(root);
    }
}
