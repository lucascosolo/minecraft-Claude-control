package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** F1: the validated traits object shared by entities set, spawn and routines, with its clamp table and authority decisions. */
record TraitSpec(Map<String, Double> attributes, Map<String, Object> traits) {
    static final int MAX_ENTITIES = 64;
    static final List<String> FLAGS = List.of("glowing", "silent", "no_gravity", "no_ai", "invisible", "on_fire", "persistent",
            "can_pick_up_items", "collidable", "invulnerable");
    private static final List<String> BOOLS = List.of("name_visible", "baby", "sheared", "saddled", "sitting", "angry", "charged");
    static final List<String> SLOTS = List.of("head", "chest", "legs", "feet", "mainhand", "offhand");
    private static final Set<String> COLORS = Set.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black");

    /** min..max for everyone, pmin..pmax for owners; relative rows are multiples of the type's base value. */
    private record Range(double min, double max, double pmin, double pmax, boolean relative) {}

    private static final Map<String, Range> TABLE = new LinkedHashMap<>();

    static {
        row("scale", 0.1, 8, 0.05, 16);
        row("max_health", 1, 200, 1, 1024);
        TABLE.put("movement_speed", new Range(0, 4, 0, 4, true));
        TABLE.put("flying_speed", new Range(0, 4, 0, 4, true));
        TABLE.put("sneaking_speed", new Range(0, 1, 0, 1, false));
        row("attack_damage", 0, 20, 0, 2048);
        row("attack_knockback", 0, 5, 0, 5);
        row("attack_speed", 0, 16, 0, 1024);
        row("jump_strength", 0, 2, 0, 32);
        row("follow_range", 0, 64, 0, 2048);
        row("knockback_resistance", 0, 1, 0, 1);
        row("explosion_knockback_resistance", 0, 1, 0, 1);
        row("armor", 0, 30, 0, 30);
        row("armor_toughness", 0, 20, 0, 20);
        row("gravity", 0.01, 0.16, -1, 1);
        row("step_height", 0, 3, 0, 10);
        row("safe_fall_distance", 0, 64, 0, 1024);
        row("fall_damage_multiplier", 0, 2, 0, 100);
        row("block_interaction_range", 0, 8, 0, 64);
        row("entity_interaction_range", 0, 8, 0, 64);
        row("block_break_speed", 0, 4, 0, 1024);
        row("luck", -16, 16, -1024, 1024);
        row("max_absorption", 0, 20, 0, 2048);
        row("burning_time", 0, 2, 0, 1024);
        row("oxygen_bonus", 0, 16, 0, 1024);
        row("water_movement_efficiency", 0, 1, 0, 1);
        row("movement_efficiency", 0, 1, 0, 1);
        row("mining_efficiency", 0, 64, 0, 1024);
        row("submerged_mining_speed", 0, 4, 0, 20);
        row("sweeping_damage_ratio", 0, 1, 0, 1);
        row("tempt_range", 0, 32, 0, 2048);
        row("spawn_reinforcements", 0, 0.5, 0, 1);
    }

    private static void row(String id, double min, double max, double pmin, double pmax) {
        TABLE.put(id, new Range(min, max, pmin, pmax, false));
    }

    private static final Map<String, Set<String>> LOOKS = Map.of(
            "color", Set.of("sheep", "shulker", "wolf", "cat", "tropical_fish"),
            "variant", Set.of("cat", "wolf", "frog", "axolotl", "parrot", "horse", "llama", "trader_llama", "rabbit", "fox", "mooshroom", "panda",
                    "villager", "zombie_villager"),
            "profession", Set.of("villager"), "level", Set.of("villager"), "size", Set.of("slime", "magma_cube", "phantom"),
            "sheared", Set.of("sheep", "mooshroom", "snow_golem", "bogged"), "saddled", Set.of("pig", "strider", "horse", "donkey", "mule", "camel"),
            "sitting", Set.of("wolf", "cat", "parrot", "fox"), "angry", Set.of("wolf", "bee", "zombified_piglin", "enderman"),
            "charged", Set.of("creeper"));

    static Set<String> attributeIds() { return TABLE.keySet(); }

    boolean isEmpty() { return attributes.isEmpty() && traits.isEmpty(); }

    static TraitSpec parse(JsonNode n) {
        PlaceExpr.obj(n, "traits");
        List<String> keys = new ArrayList<>(List.of("attributes", "name", "health", "color", "variant", "profession", "level", "size",
                "explosion_radius", "tamed_by", "leashed_to", "riding", "passenger", "target", "equipment", "velocity", "facing"));
        keys.addAll(FLAGS);
        keys.addAll(BOOLS);
        PlaceExpr.strict(n, "traits", keys.toArray(String[]::new));
        Map<String, Double> attributes = new LinkedHashMap<>();
        Map<String, Object> traits = new LinkedHashMap<>();
        for (var it = n.fields(); it.hasNext(); ) {
            var f = it.next();
            String k = f.getKey();
            JsonNode v = f.getValue();
            if (FLAGS.contains(k) || BOOLS.contains(k)) {
                if (!v.isBoolean()) throw new IllegalArgumentException(k + " must be true or false");
                traits.put(k, v.asBoolean());
                continue;
            }
            switch (k) {
                case "attributes" -> {
                    PlaceExpr.obj(v, "attributes");
                    for (var a = v.fields(); a.hasNext(); ) {
                        var e = a.next();
                        String id = e.getKey().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
                        if (!TABLE.containsKey(id)) throw new IllegalArgumentException("unknown attribute '" + e.getKey() + "'; use " + TABLE.keySet());
                        if (!e.getValue().isNumber() || !Double.isFinite(e.getValue().asDouble()))
                            throw new IllegalArgumentException("attribute " + id + " must be a number");
                        attributes.put(id, e.getValue().asDouble());
                    }
                }
                case "name" -> traits.put(k, PlaceExpr.text(n, k, true, 64));
                case "health" -> traits.put(k, PlaceExpr.num(n, k, null, 0.5, 1024));
                case "level" -> traits.put(k, PlaceExpr.integer(n, k, null, 1, 5));
                case "size" -> traits.put(k, PlaceExpr.integer(n, k, null, 1, 8));
                case "explosion_radius" -> traits.put(k, PlaceExpr.integer(n, k, null, 0, 8));
                case "color" -> {
                    String c = PlaceExpr.text(n, k, true, 16);
                    if (!COLORS.contains(c)) throw new IllegalArgumentException("color must be a dye colour, not '" + c + "'");
                    traits.put(k, c);
                }
                case "variant", "profession" -> traits.put(k, PlaceExpr.id(PlaceExpr.text(n, k, true, 64), k));
                case "tamed_by", "leashed_to" -> traits.put(k, PlaceExpr.player(v, k));
                case "riding", "passenger" -> traits.put(k, uuid(v, k));
                case "target" -> traits.put(k, isUuid(v.asText()) ? v.asText() : PlaceExpr.player(v, k));
                case "equipment" -> {
                    Map<String, ItemSpec> eq = new LinkedHashMap<>();
                    for (var it2 = PlaceExpr.obj(v, k).fields(); it2.hasNext(); ) {
                        var e = it2.next();
                        if (!SLOTS.contains(e.getKey())) throw new IllegalArgumentException("unknown equipment slot '" + e.getKey() + "'; use " + SLOTS);
                        eq.put(e.getKey(), ItemSpec.parse(e.getValue(), 64));
                    }
                    traits.put(k, Map.copyOf(eq));
                }
                case "velocity" -> {
                    PlaceExpr.obj(v, k);
                    PlaceExpr.strict(v, k, "x", "y", "z");
                    List<Double> out = new ArrayList<>();
                    for (String c : List.of("x", "y", "z")) out.add(Math.max(-4, Math.min(4, PlaceExpr.num(v, c, 0.0, -1e6, 1e6))));
                    traits.put(k, List.copyOf(out));
                }
                case "facing" -> {
                    PlaceExpr.obj(v, k);
                    PlaceExpr.strict(v, k, "yaw", "pitch");
                    try {
                        traits.put(k, List.of(PlaceExpr.num(v, "yaw", 0.0, -180, 180), PlaceExpr.num(v, "pitch", 0.0, -90, 90)));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("facing " + e.getMessage());
                    }
                }
                default -> throw new IllegalArgumentException("unknown trait '" + k + "'");
            }
        }
        return new TraitSpec(java.util.Collections.unmodifiableMap(attributes), java.util.Collections.unmodifiableMap(traits));
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return s.length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String uuid(JsonNode v, String k) {
        if (!v.isTextual() || !isUuid(v.asText())) throw new IllegalArgumentException(k + " must be an entity id (UUID)");
        return v.asText();
    }

    static double clamp(String attr, double value, double base, boolean privileged) {
        Range r = TABLE.get(attr);
        if (r == null) throw new IllegalArgumentException("unknown attribute '" + attr + "'");
        double lo = privileged ? r.pmin() : r.min(), hi = privileged ? r.pmax() : r.max();
        if (r.relative()) { lo *= base; hi *= base; }
        return Math.max(lo, Math.min(hi, value));
    }

    /** Null when a look trait applies to the type (or the trait is not type-tabled), else why it was skipped. */
    static String notFor(String trait, String entityType) {
        Set<String> types = LOOKS.get("explosion_radius".equals(trait) ? "charged" : trait);
        return types == null || types.contains(entityType) ? null : trait + " does not apply to " + entityType;
    }

    boolean hostileMaking() {
        return traits.containsKey("target") || Boolean.TRUE.equals(traits.get("angry")) || attributes.getOrDefault("attack_damage", 0.0) > 6;
    }

    /** H3: on a hostile mob also invulnerable, persistent, and follow_range or movement_speed above the type's base (a missing base counts). */
    boolean hostileMaking(boolean hostileMob, Map<String, Double> bases) {
        if (hostileMaking()) return true;
        if (!hostileMob) return false;
        if (Boolean.TRUE.equals(traits.get("invulnerable")) || Boolean.TRUE.equals(traits.get("persistent"))) return true;
        for (String id : List.of("follow_range", "movement_speed"))
            if (attributes.containsKey(id) && (!bases.containsKey(id) || attributes.get(id) > bases.get(id))) return true;
        return false;
    }

    static final double REACH = 64;

    /** H3: a non-privileged turn works only within 64 blocks of one of its players. */
    static String reachRefusal(double distance, Authority a) {
        return a.privileged() || distance <= REACH ? null
                : "Only entities within 64 blocks of a player of this turn can be changed; an owner could allow more.";
    }

    /** H1: a relation that resolved to a player. */
    static String relationRefusal(String trait, String playerName, Authority a) {
        return switch (trait) {
            case "target", "leashed_to" -> a.privileged() ? null : trait + " " + playerName + " is refused: only an owner could allow a player here.";
            case "riding", "passenger" -> a.privileged() && (a.players().size() == 1 && among(playerName, a) || !a.deferred()) ? null
                    : trait + " with player " + playerName + " is refused: only that player alone in an owner's turn, or an owner asking, could allow it.";
            default -> null;
        };
    }

    /** H4: the type's base for a relative clamp: the value recorded before Claude first changed it, else the current base. */
    static double baseFor(String attr, Map<String, String> recordedBefore, double currentBase) {
        try {
            String v = recordedBefore.get("attribute." + attr);
            return v == null ? currentBase : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return currentBase;
        }
    }

    private static final Map<String, Double> PLAYER_BASE = Map.of("movement_speed", 0.1, "flying_speed", 0.05);

    /** H4: vanilla player defaults for relative clamps. */
    static double playerBase(String attr, double fallback) { return PLAYER_BASE.getOrDefault(attr, fallback); }

    /** H5: attributes first, so health and the rest see the new maximum. */
    static List<String> applyOrder(java.util.Collection<String> keys) {
        List<String> out = new ArrayList<>(keys.stream().filter(k -> k.startsWith("attribute.")).toList());
        keys.stream().filter(k -> !k.startsWith("attribute.")).forEach(out::add);
        return out;
    }

    /** H6: a stored restore record is clamped and item-gated again before it is applied. */
    static Map<String, String> recheck(Map<String, String> stored, java.util.function.Function<String, Double> baseOf,
                                       java.util.function.Function<String, String> itemIdOf, Authority a) {
        Map<String, String> out = new LinkedHashMap<>();
        stored.forEach((k, v) -> {
            try {
                if (k.startsWith("attribute.")) {
                    String id = k.substring(10);
                    double d = Double.parseDouble(v);
                    if (TABLE.containsKey(id) && Double.isFinite(d)) out.put(k, String.valueOf(clamp(id, d, baseOf.apply(id), true)));
                } else if (k.startsWith("equipment.")) {
                    if (v.isEmpty()) out.put(k, v);
                    else {
                        String item = itemIdOf.apply(v);
                        if (item != null && SuiteGates.item(item, a) == null) out.put(k, v);
                    }
                } else {
                    switch (k) {
                        case "health" -> {
                            double d = Double.parseDouble(v);
                            if (Double.isFinite(d)) out.put(k, String.valueOf(Math.max(0.5, Math.min(1024, d))));
                        }
                        case "level" -> out.put(k, String.valueOf(Math.max(1, Math.min(5, Integer.parseInt(v)))));
                        case "size" -> out.put(k, String.valueOf(Math.max(1, Math.min(8, Integer.parseInt(v)))));
                        case "explosion_radius" -> out.put(k, String.valueOf(Math.max(0, Math.min(8, Integer.parseInt(v)))));
                        default -> out.put(k, v);
                    }
                }
            } catch (RuntimeException ignored) {
                // unreadable: dropped
            }
        });
        return out;
    }

    String callRefusal(Authority a, int seconds) {
        if (a.privileged()) return null;
        if (Boolean.TRUE.equals(traits.get("invulnerable")) && seconds == 0)
            return "invulnerable without seconds is refused: give seconds, or only an owner could make it permanent.";
        if (traits.containsKey("charged") || traits.containsKey("explosion_radius"))
            return "charged and explosion_radius are refused: only an owner could allow them.";
        if (traits.containsKey("target") && !isUuid((String) traits.get("target")))
            return "A player as target is refused: only an owner could allow it.";
        if (traits.containsKey("leashed_to")) return "leashed_to a player is refused: only an owner could allow it.";
        return null;
    }

    /** riders: players riding the entity or carrying it; turnDistance: to the nearest player of the turn; bases: the type's attribute bases. */
    record Facts(String type, boolean player, boolean grave, boolean otherPlugin, String tamedOwner, List<String> playersNear,
                 List<String> riders, double turnDistance, boolean hostileMob, Map<String, Double> bases) {
        Facts(String type, boolean player, boolean grave, boolean otherPlugin, String tamedOwner, List<String> playersNear) {
            this(type, player, grave, otherPlugin, tamedOwner, playersNear, List.of(), 0, false, Map.of());
        }
    }

    String entityRefusal(Facts f, Authority a) {
        if (f.player()) return "players are never changed here; use the player tool's attributes action.";
        if (f.grave()) return "graves are never changed.";
        if (f.otherPlugin()) return "entities of other plugins are never changed.";
        if (a.privileged()) return null;
        if (f.tamedOwner() != null && !among(f.tamedOwner(), a)) return "tamed by " + f.tamedOwner() + ", who is not part of this turn.";
        for (String r : f.riders()) if (!among(r, a)) return "carrying or carried by " + r + ", who is not part of this turn.";
        String far = reachRefusal(f.turnDistance(), a);
        if (far != null) return far;
        if (hostileMaking(f.hostileMob(), f.bases())) for (String p : f.playersNear()) if (!among(p, a))
            return "hostile-making traits near " + p + " need an owner.";
        return null;
    }

    static String playerAttributes(String target, boolean permanent, Authority a) { return playerAttributes(target, permanent, false, a); }

    static String playerAttributes(String target, boolean permanent, boolean clears, Authority a) {
        if (a.privileged()) return null;
        if (clears) return "Clearing attributes is refused: only an owner could allow it.";
        if (!among(target, a)) return "Changing " + target + "'s attributes is refused: a player may change only their own; an owner could allow it.";
        if (permanent) return "permanent attributes are refused: only an owner could allow them.";
        return null;
    }

    private static boolean among(String name, Authority a) {
        return a.players().stream().anyMatch(p -> p.equalsIgnoreCase(name));
    }

    record Restore(Map<String, String> put, List<String> changedSince) {}

    static Restore restore(Map<String, String> before, Map<String, String> after, Map<String, String> now) {
        Map<String, String> put = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        for (String k : reversed(before.keySet())) {
            if (now.containsKey(k) && java.util.Objects.equals(now.get(k), after.get(k))) put.put(k, before.get(k));
            else changed.add(k);
        }
        return new Restore(put, changed.reversed());
    }

    private static List<String> reversed(java.util.Collection<String> keys) { return new ArrayList<>(keys).reversed(); }

    /** H9: one owner's restore record on an entity: what it changed and what it set. */
    record Hold(String id, long until, Map<String, String> before, Map<String, String> after) {}

    /** put: what to write back (reverse order); handoff: per other hold, the original values it now owns. */
    record Ending(Map<String, String> put, List<String> changedSince, Map<String, Map<String, String>> handoff) {}

    static Ending end(Hold ending, java.util.Collection<Hold> others, Map<String, String> now) {
        Map<String, String> put = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        Map<String, Map<String, String>> handoff = new LinkedHashMap<>();
        for (String k : reversed(ending.before().keySet())) {
            String cur = now.get(k);
            Hold owner = cur == null ? null : others.stream().filter(o -> cur.equals(o.after().get(k))).findFirst().orElse(null);
            if (owner != null) handoff.computeIfAbsent(owner.id(), x -> new LinkedHashMap<>()).put(k, ending.before().get(k));
            else if (cur != null && cur.equals(ending.after().get(k))) put.put(k, ending.before().get(k));
            else changed.add(k);
        }
        return new Ending(put, changed.reversed(), handoff);
    }

    static int seconds(JsonNode setArgs) {
        return setArgs.has("seconds") ? PlaceExpr.integer(setArgs, "seconds", null, 1, 3600) : 0;
    }
}
