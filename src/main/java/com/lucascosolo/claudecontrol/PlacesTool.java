package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.bukkit.Location;

/** places: save, list, delete and go (through teleport_player's safety checks). */
final class PlacesTool implements SuiteTool, ReportSection {
    private static final ObjectMapper M = new ObjectMapper();
    private final PlaceBook book;
    private final SuiteTools suite;
    private final GameTools game;

    PlacesTool(PlaceBook book, SuiteTools suite, GameTools game) {
        this.book = book;
        this.suite = suite;
        this.game = game;
    }

    @Override
    public ToolOutcome run(JsonNode in, Authority a, String actors) throws GameTools.ToolError, InterruptedException {
        String action = in.path("action").asText("");
        String name = in.path("name").isTextual() ? in.get("name").asText() : null;
        switch (action) {
            case "list" -> {
                ArrayNode l = M.createArrayNode();
                for (PlaceBook.Place p : book.list()) l.add(p.name() + ": " + p.world() + " " + p.x() + " " + p.y() + " " + p.z());
                return ToolOutcome.of(M.createObjectNode().set("places", l).toString());
            }
            case "save" -> {
                if (!in.has("at")) throw new GameTools.ToolError("save needs at: a place.");
                PlaceExpr at = PlaceExpr.parse(in.get("at"));
                Location l = SuiteTools.point(at, suite.base(at));
                book.save(new PlaceBook.Place(need(name), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
                return ToolOutcome.of(M.createObjectNode().put("saved", name).put("at", l.getWorld().getName() + " " + l.getBlockX() + " "
                        + l.getBlockY() + " " + l.getBlockZ()).toString());
            }
            case "delete" -> {
                if (!book.delete(need(name))) throw new GameTools.ToolError("no saved place called " + name + ".");
                return ToolOutcome.of(M.createObjectNode().put("deleted", name).toString());
            }
            case "go" -> {
                PlaceBook.Place p = book.get(need(name));
                String player = in.path("player").asText("");
                if (player.isEmpty()) throw new GameTools.ToolError("go needs player.");
                SuiteTools.refuse(SuiteGates.teleport(player, a));
                return ToolOutcome.of(game.teleportPlayer(player, p.world(), p.x(), p.y(), p.z()));
            }
            default -> throw new GameTools.ToolError("action must be save, list, delete or go.");
        }
    }

    @Override
    public java.util.List<String> reportLines() {
        java.util.List<PlaceBook.Place> saved = book.list();
        if (saved.isEmpty()) return java.util.List.of();
        StringBuilder sb = new StringBuilder("Saved places: ");
        for (int i = 0; i < Math.min(8, saved.size()); i++) {
            PlaceBook.Place p = saved.get(i);
            sb.append(i == 0 ? "" : "; ").append(WakeStore.quote(p.name())).append(" ").append(p.world()).append(" ").append(p.x()).append(" ")
                    .append(p.y()).append(" ").append(p.z());
        }
        return java.util.List.of(sb.append(saved.size() > 8 ? "; ..." : "").toString());
    }

    PlaceBook book() { return book; }

    private static String need(String name) throws GameTools.ToolError {
        if (name == null || name.isBlank()) throw new GameTools.ToolError("name is required.");
        return name;
    }
}
