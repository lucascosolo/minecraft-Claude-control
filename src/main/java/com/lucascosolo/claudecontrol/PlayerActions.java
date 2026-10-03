package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validated arguments of the player tool: a player name and an ordered action list. */
record PlayerActions(String name, List<Action> actions) {
    record Action(String kind, EffectSpec effect, List<ItemSpec> items, String item, int count, int levels, String mode,
                  PlaceExpr place, int seconds, Map<String, Double> attributes, boolean permanent) {
        Action(String kind, EffectSpec effect, List<ItemSpec> items, String item, int count, int levels, String mode, PlaceExpr place, int seconds) {
            this(kind, effect, items, item, count, levels, mode, place, seconds, null, false);
        }
    }

    /** F6: player attributes; reach sets both interaction ranges. */
    static final List<String> ATTRIBUTES = List.of("scale", "movement_speed", "jump_strength", "gravity", "step_height", "max_health", "reach");

    private static final List<String> MODES = List.of("survival", "creative", "adventure", "spectator");
    private static final List<String> PLAIN = List.of("heal", "feed", "clear_effects", "extinguish");

    List<String> kinds() { return actions.stream().map(Action::kind).toList(); }

    static PlayerActions parse(JsonNode n) {
        PlaceExpr.obj(n, "player");
        PlaceExpr.strict(n, "player", "name", "actions");
        String name = PlaceExpr.player(n.get("name"), "name");
        List<Action> out = new ArrayList<>();
        for (JsonNode a : PlaceExpr.array(n, "actions", 1, 20)) out.add(action(PlaceExpr.obj(a, "action")));
        return new PlayerActions(name, List.copyOf(out));
    }

    private static Action action(JsonNode a) {
        String kind = PlaceExpr.text(a, "action", true, 32);
        if (PLAIN.contains(kind)) {
            PlaceExpr.strict(a, kind, "action");
            return new Action(kind, null, null, null, 0, 0, null, null, 0);
        }
        switch (kind) {
            case "effect" -> {
                ObjectNode e = a.deepCopy();
                e.remove("action");
                return new Action(kind, EffectSpec.parse(e), null, null, 0, 0, null, null, 0);
            }
            case "give" -> {
                PlaceExpr.strict(a, kind, "action", "items");
                List<ItemSpec> items = new ArrayList<>();
                for (JsonNode i : PlaceExpr.array(a, "items", 1, 36)) items.add(ItemSpec.parse(i, 64));
                return new Action(kind, null, List.copyOf(items), null, 0, 0, null, null, 0);
            }
            case "take" -> {
                PlaceExpr.strict(a, kind, "action", "item", "count");
                String item = PlaceExpr.id(PlaceExpr.text(a, "item", true, 64), "item");
                return new Action(kind, null, null, item, PlaceExpr.integer(a, "count", 1, 1, 64 * 36), 0, null, null, 0);
            }
            case "xp" -> {
                PlaceExpr.strict(a, kind, "action", "levels");
                return new Action(kind, null, null, null, 0, PlaceExpr.integer(a, "levels", null, -100, 100), null, null, 0);
            }
            case "gamemode" -> {
                PlaceExpr.strict(a, kind, "action", "mode");
                String mode = PlaceExpr.text(a, "mode", true, 16);
                if (!MODES.contains(mode)) throw new IllegalArgumentException("unknown gamemode '" + mode + "'; use " + MODES);
                return new Action(kind, null, null, null, 0, 0, mode, null, 0);
            }
            case "set_spawn" -> {
                PlaceExpr.strict(a, kind, "action", "place");
                if (!a.has("place")) throw new IllegalArgumentException("place is required");
                return new Action(kind, null, null, null, 0, 0, null, PlaceExpr.parse(a.get("place")), 0);
            }
            case "protect" -> {
                PlaceExpr.strict(a, kind, "action", "seconds");
                return new Action(kind, null, null, null, 0, 0, null, null, PlaceExpr.integer(a, "seconds", 30, 1, 60));
            }
            case "attributes" -> {
                PlaceExpr.strict(a, kind, "action", "set", "seconds", "permanent");
                JsonNode set = PlaceExpr.obj(a.get("set"), "set");
                if (set.isEmpty()) throw new IllegalArgumentException("set must name at least one attribute");
                Map<String, Double> attrs = new LinkedHashMap<>();
                for (var it = set.fields(); it.hasNext(); ) {
                    var f = it.next();
                    String id = f.getKey().toLowerCase(java.util.Locale.ROOT).replaceFirst("^minecraft:", "");
                    if (!ATTRIBUTES.contains(id)) throw new IllegalArgumentException("attribute '" + f.getKey() + "' is not for players; use " + ATTRIBUTES);
                    if (!f.getValue().isNumber() || !Double.isFinite(f.getValue().asDouble())) throw new IllegalArgumentException(id + " must be a number");
                    attrs.put(id, f.getValue().asDouble());
                }
                boolean permanent = PlaceExpr.bool(a, "permanent");
                if (permanent && a.has("seconds")) throw new IllegalArgumentException("give seconds or permanent, not both");
                return new Action(kind, null, null, null, 0, 0, null, null, PlaceExpr.integer(a, "seconds", 300, 1, 1800), Map.copyOf(attrs), permanent);
            }
            default -> throw new IllegalArgumentException("unknown action '" + kind + "'");
        }
    }
}
