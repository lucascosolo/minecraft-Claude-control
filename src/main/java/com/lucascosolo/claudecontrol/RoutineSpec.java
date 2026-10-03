package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validated routine: general form, or the rain shorthand expanded into it. In an expansion for a follow list of
 * more than one player (or "all") the spawn places use near "$player"; the engine substitutes each followed player.
 */
record RoutineSpec(String label, List<String> follow, boolean followAll, PlaceExpr at, int seconds, int everyTicks, int maxAlive,
                   List<Step> steps, List<Step> onEnd) {
    record Step(String tool, JsonNode args, double chance, double weight) {}

    private static final ObjectMapper M = new ObjectMapper();
    private static final Set<String> TOOLS = Set.of("spawn", "show", "player", "entities");
    private static final Set<String> HOSTILE = Set.of("zombie", "husk", "drowned", "skeleton", "stray", "bogged", "wither_skeleton",
            "creeper", "spider", "cave_spider", "enderman", "endermite", "silverfish", "witch", "blaze", "ghast", "phantom", "slime",
            "magma_cube", "pillager", "vindicator", "evoker", "ravager", "vex", "warden", "wither", "ender_dragon", "zombified_piglin",
            "zombie_villager", "piglin_brute", "hoglin", "zoglin", "guardian", "elder_guardian", "shulker", "breeze", "creaking");

    double spawnsPerSecondPerPlayer() {
        double sum = 0;
        for (Step s : steps) {
            if (s.tool().equals("spawn")) sum += s.args().path("count").asInt(1) * 20.0 / everyTicks;
            if (s.tool().equals("show")) sum += s.args().path("fireworks").path("count").asInt(0) * 20.0 / everyTicks;
        }
        return sum;
    }

    /** Steady-state particles per second: each run shows count per second for seconds, and runs overlap. */
    double particlesPerSecond() {
        double sum = 0;
        for (Step s : steps) {
            JsonNode p = s.args().path("particles");
            if (s.tool().equals("show") && p.isObject()) sum += p.path("count").asInt(0) * Math.max(1, p.path("seconds").asInt(1)) * 20.0 / everyTicks;
        }
        return sum;
    }

    static RoutineSpec parse(JsonNode n) {
        PlaceExpr.obj(n, "routine");
        PlaceExpr.strict(n, "routine", "label", "follow", "at", "seconds", "every_ticks", "max_alive", "do", "on_end", "rain");
        String label = PlaceExpr.text(n, "label", true, 32);
        if (!label.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("label must be 1..32 of letters, digits, _ or -");
        if (n.has("follow") == n.has("at")) throw new IllegalArgumentException("routine needs exactly one of follow and at");
        List<String> follow = null;
        boolean all = false;
        PlaceExpr at = null;
        if (n.has("at")) {
            at = PlaceExpr.parse(n.get("at"));
        } else if (n.get("follow").isTextual() && n.get("follow").asText().equals("all")) {
            all = true;
            follow = List.of();
        } else {
            follow = new ArrayList<>();
            for (JsonNode p : PlaceExpr.array(n, "follow", 1, 20)) follow.add(PlaceExpr.player(p, "follow"));
        }
        int seconds = PlaceExpr.integer(n, "seconds", null, 1, 600);
        int maxAlive = PlaceExpr.integer(n, "max_alive", 150, 1, 150);
        List<Step> onEnd = new ArrayList<>();
        if (n.has("on_end")) for (JsonNode s : PlaceExpr.array(n, "on_end", 0, 20)) onEnd.add(step(s));
        List<Step> steps = new ArrayList<>();
        int every;
        if (n.has("rain")) {
            if (n.has("do") || n.has("every_ticks")) throw new IllegalArgumentException("rain replaces do and every_ticks");
            every = rain(n.get("rain"), n, follow, all, at, steps);
        } else {
            every = PlaceExpr.integer(n, "every_ticks", null, 2, Integer.MAX_VALUE);
            for (JsonNode s : PlaceExpr.array(n, "do", 1, 20)) steps.add(step(s));
        }
        RoutineSpec r = new RoutineSpec(label, follow, all, at, seconds, every, maxAlive, List.copyOf(steps), List.copyOf(onEnd));
        if (r.spawnsPerSecondPerPlayer() > 10 + 1e-9)
            throw new IllegalArgumentException("at most 10 spawned entities (fireworks included) per second per followed player, this routine does "
                    + r.spawnsPerSecondPerPlayer());
        if (r.particlesPerSecond() > 200 + 1e-9)
            throw new IllegalArgumentException("at most 200 particles per second, this routine shows " + r.particlesPerSecond());
        return r;
    }

    private static Step step(JsonNode s) {
        PlaceExpr.obj(s, "step");
        PlaceExpr.strict(s, "step", "tool", "args", "chance", "weight");
        String tool = PlaceExpr.text(s, "tool", true, 64);
        if (!TOOLS.contains(tool)) throw new IllegalArgumentException("tool '" + tool + "' is not allowed in a routine; use " + TOOLS);
        JsonNode args = s.has("args") ? PlaceExpr.obj(s.get("args"), "args") : M.createObjectNode();
        if (tool.equals("spawn")) {
            PlaceExpr.integer(args, "count", 1, 1, 30);
            if (!args.has("lifetime_seconds")) args = ((ObjectNode) args.deepCopy()).put("lifetime_seconds", 60);
        }
        if (tool.equals("player"))
            for (JsonNode a : args.path("actions"))
                if (!"effect".equals(a.path("action").asText()))
                    throw new IllegalArgumentException("a player step in a routine may only give an effect, not '" + a.path("action").asText() + "'");
        return new Step(tool, args, PlaceExpr.num(s, "chance", 1.0, 0, 1), PlaceExpr.num(s, "weight", 1.0, Double.MIN_NORMAL, 1000));
    }

    /** Expands the rain shorthand into steps; returns every_ticks. */
    private static int rain(JsonNode r, JsonNode top, List<String> follow, boolean all, PlaceExpr at, List<Step> out) {
        PlaceExpr.obj(r, "rain");
        PlaceExpr.strict(r, "rain", "what", "per_second", "radius", "height", "cloud", "landing", "lifetime_seconds");
        int perSecond = PlaceExpr.integer(r, "per_second", null, 1, 10), radius = PlaceExpr.integer(r, "radius", null, 1, 24),
                height = PlaceExpr.integer(r, "height", null, 8, 40), life = PlaceExpr.integer(r, "lifetime_seconds", null, 1, 120);
        String landing = PlaceExpr.text(r, "landing", true, 16);
        if (!Set.of("soft", "bounce", "vanish").contains(landing))
            throw new IllegalArgumentException("landing must be soft, bounce or vanish, not '" + landing + "'");
        List<JsonNode> what = PlaceExpr.array(r, "what", 1, 10);
        ObjectNode place = at != null ? ((ObjectNode) top.get("at")).deepCopy() : M.createObjectNode();
        if (at == null) place.put("near", follow != null && follow.size() == 1 ? follow.get(0) : "$player");
        ObjectNode scatter = place.putObject("scatter");
        scatter.put("radius", radius);
        scatter.putArray("height").add(height).add(height);
        PlaceExpr.parse(place);
        for (JsonNode w : what) {
            PlaceExpr.obj(w, "what entry");
            PlaceExpr.strict(w, "what entry", "type", "item", "weight");
            if (w.has("type") == w.has("item")) throw new IllegalArgumentException("each what entry needs exactly one of type and item");
            ObjectNode args = M.createObjectNode();
            if (w.has("type")) {
                String type = PlaceExpr.id(PlaceExpr.text(w, "type", true, 64), "type");
                if (HOSTILE.contains(type) && !landing.equals("vanish"))
                    throw new IllegalArgumentException("hostile type '" + type + "' needs landing 'vanish'");
                args.put("type", type);
            } else {
                args.put("type", "item");
                args.putArray("items").addObject().put("id", PlaceExpr.id(PlaceExpr.text(w, "item", true, 64), "item")).put("count", 1);
            }
            args.set("at", place.deepCopy());
            args.put("count", 1).put("landing", landing).put("lifetime_seconds", life);
            out.add(new Step("spawn", args, 1.0, PlaceExpr.num(w, "weight", 1.0, Double.MIN_NORMAL, 1000)));
        }
        if (PlaceExpr.bool(r, "cloud")) {
            ObjectNode args = M.createObjectNode();
            if (all || at != null) args.put("to", "all");
            else { ArrayNode to = args.putArray("to"); follow.forEach(to::add); }
            ObjectNode p = args.putObject("particles");
            p.set("at", place.deepCopy());
            p.put("id", "cloud").put("count", 20).put("seconds", 1);
            out.add(new Step("show", args, 1.0, 1.0));
        }
        return (int) Math.max(2, Math.ceil(20.0 * what.size() / perSecond));
    }
}
