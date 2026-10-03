package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * quest: shared progress kept in QuestBook and drawn for every online player on that player's own scheduler. A sidebar
 * quest uses a per-player scoreboard; if the server refuses scoreboards (Folia does), it falls back to a bossbar and says so.
 * The reward on finish is a player give, so it passes the same item gates.
 */
final class QuestTool implements SuiteTool, Listener, ReportSection {
    private static final ObjectMapper M = new ObjectMapper();
    private final QuestBook book;
    private final SuiteTools suite;
    private final PlayerTool give;
    private final Map<String, BossBar> bars = new ConcurrentHashMap<>();
    private final Set<UUID> sidebars = ConcurrentHashMap.newKeySet();
    private volatile String sidebarFailure;

    QuestTool(QuestBook book, SuiteTools suite, PlayerTool give) {
        this.book = book;
        this.suite = suite;
        this.give = give;
        try {
            Bukkit.getScoreboardManager().getNewScoreboard();
        } catch (UnsupportedOperationException | IllegalStateException | NullPointerException e) {
            sidebarFailure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    /** How sidebar quests are drawn on this server, for the tool result and the report. */
    String renderPath() { return sidebarFailure == null ? "sidebar scoreboard" : "bossbar (scoreboards refused: " + sidebarFailure + ")"; }

    @Override
    public ToolOutcome run(JsonNode in, Authority a, String actors) throws GameTools.ToolError, InterruptedException {
        String action = in.path("action").asText("");
        String id = in.path("id").asText(null);
        ObjectNode out = M.createObjectNode();
        List<ChangeLog.Change> changes = new ArrayList<>();
        switch (action) {
            case "start" -> {
                List<String> steps = new ArrayList<>();
                in.path("steps").forEach(s -> steps.add(s.asText()));
                if (in.hasNonNull("reward")) SuiteTools.refuse(rewardRefusal(in.get("reward"), a));
                book.start(id, in.path("title").asText(null), steps, in.path("show").asText("sidebar"), in.get("reward"), a);
                out.put("started", id);
            }
            case "update" -> {
                List<Integer> done = new ArrayList<>();
                for (JsonNode d : in.path("done")) {
                    if (!d.canConvertToInt()) throw new GameTools.ToolError("done must be step numbers.");
                    done.add(d.asInt());
                }
                book.update(id, done);
                out.put("updated", id);
            }
            case "finish" -> {
                QuestBook.Claimed claimed = book.claim(String.valueOf(id), a);
                if (claimed.reward() != null) {
                    List<String> given = new ArrayList<>();
                    JsonNode items = claimed.reward().path("items");
                    int n = 0;
                    try {
                        for (; n < items.size(); n++) {
                            ObjectNode call = M.createObjectNode().put("name", claimed.reward().get("to").asText());
                            call.putArray("actions").addObject().put("action", "give").putArray("items").add(items.get(n));
                            ToolOutcome o = give.run(call, claimed.creator() == null ? a : claimed.creator(), actors);
                            changes.addAll(o.changes());
                            given.add(o.text());
                        }
                        out.put("reward", String.join("; ", given));
                    } catch (GameTools.ToolError | IllegalArgumentException e) {
                        book.restore(claimed, e.getMessage(), n);
                        if (n > 0) out.put("reward_given_so_far", String.join("; ", given));
                        out.put("reward_pending", e.getMessage()).put("note", "the quest stays active; finish it again when the reward can be given.");
                        refreshAll();
                        return new ToolOutcome(out.toString(), changes);
                    }
                }
                out.put("finished", id);
            }
            case "cancel" -> {
                if (!book.cancel(String.valueOf(id))) throw new GameTools.ToolError("no active quest '" + id + "'.");
                out.put("cancelled", id);
            }
            case "list" -> {
                ArrayNode l = out.putArray("quests");
                for (QuestBook.Quest q : book.list()) l.add(q.id() + ": " + q.title() + " " + q.done().size() + "/" + q.steps().size() + " (" + q.show() + ")"
                        + (q.pending() == null ? "" : " reward pending: " + q.pending()));
                return ToolOutcome.of(out.toString());
            }
            default -> throw new GameTools.ToolError("action must be start, update, finish, cancel or list.");
        }
        refreshAll();
        out.put("display", renderPath());
        return new ToolOutcome(out.toString(), changes);
    }

    void refreshAll() { for (Player p : Bukkit.getOnlinePlayers()) refresh(p); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { refresh(e.getPlayer()); }

    void refresh(Player p) {
        List<QuestBook.Quest> quests = book.list();
        Set<String> live = ConcurrentHashMap.newKeySet();
        for (QuestBook.Quest q : quests) live.add(q.id());
        bars.keySet().removeIf(qid -> {
            if (live.contains(qid)) return false;
            BossBar old = bars.get(qid);
            for (Player o : Bukkit.getOnlinePlayers()) o.getScheduler().run(suite.plugin(), t -> o.hideBossBar(old), null);
            return true;
        });
        p.getScheduler().run(suite.plugin(), t -> render(p, quests), null);
    }

    private void render(Player p, List<QuestBook.Quest> quests) {
        QuestBook.Quest side = null;
        for (QuestBook.Quest q : quests) {
            boolean bar = q.show().equals("bossbar") || (q.show().equals("sidebar") && (side != null || sidebarFailure != null));
            if (q.show().equals("sidebar") && !bar) side = q;
            if (!bar) continue;
            float progress = q.done().size() / (float) q.steps().size();
            Component name = Component.text(q.title() + " (" + q.done().size() + "/" + q.steps().size() + ")");
            BossBar b = bars.computeIfAbsent(q.id(), k -> BossBar.bossBar(name, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS));
            b.name(name).progress(progress);
            p.showBossBar(b);
        }
        if (side != null && !sidebar(p, side)) render(p, quests);
        else if (side == null && sidebars.remove(p.getUniqueId())) {
            try {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            } catch (RuntimeException ignored) {
                // the sidebar could only have been set if scoreboards work
            }
        }
    }

    private boolean sidebar(Player p, QuestBook.Quest q) {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            List<String> lines = book.sidebar(q.id());
            Objective o = sb.registerNewObjective("quest", Criteria.DUMMY, Component.text(lines.get(0)));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 1; i < lines.size(); i++) o.getScore(lines.get(i)).setScore(lines.size() - i);
            p.setScoreboard(sb);
            sidebars.add(p.getUniqueId());
            return true;
        } catch (UnsupportedOperationException | IllegalStateException | NullPointerException e) {
            sidebarFailure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return false;
        }
    }

    /** B5: the reward is checked against the starting turn's authority, with the same item gates as player give. */
    private static String rewardRefusal(JsonNode reward, Authority a) {
        for (JsonNode i : reward.path("items")) {
            ItemSpec s = ItemSpec.parse(i, 64);
            String r = SuiteGates.item(s.id(), a);
            for (var e : s.enchantments().entrySet()) if (r == null) r = SuiteGates.enchant(e.getKey() + " on " + s.id(), e.getValue(), 5, a);
            if (r != null) return r;
        }
        return null;
    }

    /** B8: hides every quest boss bar (on disable no task can be scheduled, so this runs directly). */
    void stop() {
        for (BossBar b : bars.values()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    p.hideBossBar(b);
                } catch (RuntimeException ignored) {
                    // the player is leaving too
                }
            }
        }
        bars.clear();
    }

    @Override
    public List<String> reportLines() {
        List<QuestBook.Quest> quests = book.list();
        if (quests.isEmpty()) return List.of();
        StringBuilder sb = new StringBuilder("Active quests: ");
        for (int i = 0; i < quests.size(); i++) {
            QuestBook.Quest q = quests.get(i);
            sb.append(i == 0 ? "" : "; ").append(q.id()).append(" ").append(WakeStore.quote(q.title())).append(" ").append(q.done().size()).append("/")
                    .append(q.steps().size()).append(q.pending() == null ? "" : " (reward pending)");
        }
        return List.of(sb.toString());
    }
}
