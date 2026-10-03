package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Active quests (at most 5) with their steps, progress and optional reward, persisted after every change. */
final class QuestBook {
    static final int MAX = 5;
    private static final java.util.regex.Pattern CONTROL = java.util.regex.Pattern.compile("\\p{Cntrl}");

    record Quest(String id, String title, List<String> steps, Set<Integer> done, String show, String pending) {}

    record Claimed(Quest quest, JsonNode reward, Authority creator) {}

    private record Entry(Quest quest, JsonNode reward, Authority creator) {}

    private final JsonFileStore file;
    private final List<Entry> quests = new ArrayList<>();

    QuestBook(Clock clock, Path path) {
        file = new JsonFileStore(path, clock);
        quests.addAll(JsonFileStore.entries(file.read().get("quests"), n -> {
            List<String> steps = new ArrayList<>();
            n.get("steps").forEach(s -> steps.add(s.asText()));
            Set<Integer> done = new TreeSet<>();
            n.get("done").forEach(d -> done.add(d.asInt()));
            JsonNode reward = n.hasNonNull("reward") ? n.get("reward") : null;
            Authority creator = n.hasNonNull("creator") ? Authority.fromJson(n.get("creator").asText()) : null;
            return new Entry(new Quest(n.get("id").asText(), n.get("title").asText(), List.copyOf(steps), done, n.get("show").asText(),
                    n.hasNonNull("pending") ? n.get("pending").asText() : null), reward, creator);
        }));
    }

    synchronized void start(String id, String title, List<String> steps, String show, JsonNode reward) {
        start(id, title, steps, show, reward, null);
    }

    synchronized void start(String id, String title, List<String> steps, String show, JsonNode reward, Authority creator) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("quest id must be 1..32 letters, digits, _ or -");
        if (find(id) != null) throw new IllegalArgumentException("quest '" + id + "' is already active");
        if (quests.size() >= MAX) throw new IllegalArgumentException("at most " + MAX + " active quests; finish or cancel one first");
        if (!Set.of("sidebar", "bossbar", "none").contains(show)) throw new IllegalArgumentException("show '" + show + "' must be sidebar, bossbar or none");
        if (title == null || title.length() > 40 || ChatText.plain(title).isEmpty()) throw new IllegalArgumentException("title must be 1..40 characters");
        if (CONTROL.matcher(title).find()) throw new IllegalArgumentException("title must not contain control characters");
        if (steps == null || steps.isEmpty() || steps.size() > 10) throw new IllegalArgumentException("a quest needs 1..10 steps");
        List<String> plain = new ArrayList<>();
        for (String s : steps) {
            if (s == null || s.length() > 80 || ChatText.plain(s).isEmpty()) throw new IllegalArgumentException("each step must be 1..80 characters");
            if (CONTROL.matcher(s).find()) throw new IllegalArgumentException("steps must not contain control characters");
            plain.add(ChatText.plain(s));
        }
        quests.add(new Entry(new Quest(id, ChatText.plain(title), List.copyOf(plain), new TreeSet<>(), show, null), reward(reward), creator));
        save();
    }

    synchronized void update(String id, List<Integer> done) {
        Entry e = require(id);
        int n = e.quest().steps().size();
        for (int d : done) if (d < 1 || d > n) throw new IllegalArgumentException("step numbers for '" + id + "' are 1.." + n + ", got " + d);
        e.quest().done().addAll(done);
        save();
    }

    synchronized Optional<JsonNode> reward(String id) { return Optional.ofNullable(require(id).reward()); }

    synchronized Optional<Authority> creator(String id) { return Optional.ofNullable(require(id).creator()); }

    /** The reward could not be given: the quest stays, marked with why. */
    synchronized void rewardPending(String id, String reason) {
        Entry e = require(id);
        Quest q = e.quest();
        quests.set(quests.indexOf(e), new Entry(new Quest(q.id(), q.title(), q.steps(), q.done(), q.show(), reason), e.reward(), e.creator()));
        save();
    }

    synchronized Optional<JsonNode> finish(String id) {
        Entry e = require(id);
        quests.remove(e);
        save();
        return Optional.ofNullable(e.reward());
    }

    /** D4: removes the quest for the owner or a player it was for, so a reward can only be given once. */
    synchronized Claimed claim(String id, Authority asker) {
        Entry e = require(id);
        Authority creator = e.creator() == null ? Authority.NONE : e.creator();
        String to = e.reward() == null ? null : e.reward().path("to").asText(null);
        boolean allowed = asker.privileged()
                || (to != null ? asker.players().stream().anyMatch(to::equalsIgnoreCase) : creator.players().stream().anyMatch(asker::asked));
        if (!allowed) throw new IllegalArgumentException("only an owner or the player quest '" + id + "' is for can finish it");
        quests.remove(e);
        save();
        return new Claimed(e.quest(), e.reward(), e.creator());
    }

    /** The claimed reward could not be given: the quest goes back, marked with why. */
    synchronized void restore(Claimed c, String reason) { restore(c, reason, 0); }

    /** E9: given leading reward items were handed out before the failure; they move to "given" and a retry gives the rest. */
    synchronized void restore(Claimed c, String reason, int given) {
        Quest q = c.quest();
        if (find(q.id()) != null) return;
        JsonNode reward = c.reward();
        if (reward != null && given > 0) {
            ObjectNode r = reward.deepCopy();
            com.fasterxml.jackson.databind.node.ArrayNode items = r.putArray("items"), done = r.has("given") && r.get("given").isArray()
                    ? (com.fasterxml.jackson.databind.node.ArrayNode) r.get("given") : r.putArray("given");
            for (int i = 0; i < reward.path("items").size(); i++) (i < given ? done : items).add(reward.path("items").get(i).deepCopy());
            reward = r;
        }
        quests.add(new Entry(new Quest(q.id(), q.title(), q.steps(), q.done(), q.show(), reason), reward, c.creator()));
        save();
    }

    synchronized boolean cancel(String id) {
        Entry e = find(id);
        if (e == null) return false;
        quests.remove(e);
        save();
        return true;
    }

    synchronized List<Quest> list() { return quests.stream().map(Entry::quest).toList(); }

    synchronized List<String> sidebar(String id) {
        Quest q = require(id).quest();
        List<String> lines = new ArrayList<>();
        lines.add(clip(q.title()));
        for (int i = 0; i < q.steps().size() && lines.size() < 15; i++) lines.add(clip((q.done().contains(i + 1) ? "[x] " : "[ ] ") + q.steps().get(i)));
        return lines;
    }

    private static String clip(String s) { return s.length() <= 40 ? s : s.substring(0, 40); }

    private static JsonNode reward(JsonNode r) {
        if (r == null || r.isNull()) return null;
        PlaceExpr.obj(r, "reward");
        PlaceExpr.strict(r, "reward", "to", "items");
        PlaceExpr.player(r.get("to"), "reward to");
        ObjectNode out = JsonFileStore.emptyObject();
        out.put("to", r.get("to").asText());
        ArrayNode items = out.putArray("items");
        for (JsonNode i : PlaceExpr.array(r, "items", 1, 36)) {
            ItemSpec.parse(i, 64);
            ObjectNode copy = i.deepCopy();
            if (copy.hasNonNull("name")) copy.put("name", ChatText.plain(copy.get("name").asText()));
            items.add(copy);
        }
        return out;
    }

    private Entry find(String id) {
        for (Entry e : quests) if (e.quest().id().equals(id)) return e;
        return null;
    }

    private Entry require(String id) {
        Entry e = find(id);
        if (e == null) throw new IllegalArgumentException("no active quest '" + id + "'");
        return e;
    }

    private void save() {
        ObjectNode root = JsonFileStore.emptyObject();
        ArrayNode arr = root.putArray("quests");
        for (Entry e : quests) {
            Quest q = e.quest();
            ObjectNode o = arr.addObject();
            o.put("id", q.id()).put("title", q.title()).put("show", q.show());
            q.steps().forEach(o.putArray("steps")::add);
            q.done().forEach(o.putArray("done")::add);
            if (e.reward() != null) o.set("reward", e.reward());
            if (e.creator() != null) o.put("creator", e.creator().toJson());
            if (q.pending() != null) o.put("pending", q.pending());
        }
        file.write(root);
    }
}
