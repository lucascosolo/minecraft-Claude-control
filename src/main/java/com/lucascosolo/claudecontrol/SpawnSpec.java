package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validated arguments of the spawn tool. */
record SpawnSpec(String type, PlaceExpr at, int count, int spread, int heightOffset, int lifetimeSeconds, String name, boolean baby,
                 String tamedBy, Map<String, ItemSpec> equipment, List<EffectSpec> effects, int invulnerableSeconds,
                 List<ItemSpec> items, String landing) {
    private static final List<String> SLOTS = List.of("head", "chest", "legs", "feet", "mainhand", "offhand");

    static SpawnSpec parse(JsonNode n) {
        PlaceExpr.obj(n, "spawn");
        PlaceExpr.strict(n, "spawn", "type", "at", "count", "spread", "height_offset", "lifetime_seconds", "name", "baby", "tamed_by",
                "equipment", "effects", "invulnerable_seconds", "items", "landing");
        String type = PlaceExpr.id(PlaceExpr.text(n, "type", true, 64), "type");
        if (!n.has("at")) throw new IllegalArgumentException("at is required");
        PlaceExpr at = PlaceExpr.parse(n.get("at"));
        Map<String, ItemSpec> equipment = new LinkedHashMap<>();
        if (n.has("equipment")) {
            JsonNode e = PlaceExpr.obj(n.get("equipment"), "equipment");
            for (var it = e.fields(); it.hasNext(); ) {
                var f = it.next();
                if (!SLOTS.contains(f.getKey())) throw new IllegalArgumentException("unknown equipment slot '" + f.getKey() + "'; use " + SLOTS);
                equipment.put(f.getKey(), ItemSpec.parse(f.getValue(), 64));
            }
        }
        List<EffectSpec> effects = new ArrayList<>();
        if (n.has("effects")) for (JsonNode e : PlaceExpr.array(n, "effects", 0, 10)) effects.add(EffectSpec.parse(e));
        List<ItemSpec> items = new ArrayList<>();
        if (n.has("items")) {
            if (!type.equals("item")) throw new IllegalArgumentException("items is only for type item");
            for (JsonNode i : PlaceExpr.array(n, "items", 1, 36)) items.add(ItemSpec.parse(i, 64));
        }
        if (type.equals("item") && items.isEmpty()) throw new IllegalArgumentException("type item needs a non-empty items list");
        String landing = PlaceExpr.text(n, "landing", false, 16);
        if (landing != null && !List.of("soft", "bounce", "vanish").contains(landing))
            throw new IllegalArgumentException("landing must be soft, bounce or vanish");
        String tamedBy = n.has("tamed_by") ? PlaceExpr.player(n.get("tamed_by"), "tamed_by") : null;
        return new SpawnSpec(type, at, PlaceExpr.integer(n, "count", 1, 1, 30), PlaceExpr.integer(n, "spread", 0, 0, 16),
                PlaceExpr.integer(n, "height_offset", 0, 0, 40), PlaceExpr.integer(n, "lifetime_seconds", 0, 0, 1800),
                PlaceExpr.text(n, "name", false, 64), PlaceExpr.bool(n, "baby"), tamedBy, Map.copyOf(equipment), List.copyOf(effects),
                PlaceExpr.integer(n, "invulnerable_seconds", 0, 0, 600), List.copyOf(items), landing);
    }
}
