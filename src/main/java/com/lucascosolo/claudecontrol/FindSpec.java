package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.List;

/** find: what to look for, an id, a place to search around (near) and a block/entity radius. */
record FindSpec(String what, String id, int radius, JsonNode near) {
    private static final List<String> WHAT = List.of("biome", "structure", "block", "entity", "player_bed", "saved_place");
    private static final List<String> NEEDS_ID = List.of("biome", "structure", "block", "entity");
    private static final List<String> KEYS = List.of("what", "id", "near", "radius");
    static final int MAX_RADIUS = 256, DEFAULT_RADIUS = 64, MAX_STRUCTURE_RADIUS = 128, DEFAULT_AREA_RADIUS = 128;

    static FindSpec parse(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("find arguments must be an object");
        for (Iterator<String> it = n.fieldNames(); it.hasNext();) {
            String k = it.next();
            if (!KEYS.contains(k)) throw new IllegalArgumentException("unknown key " + k);
        }
        String what = n.path("what").asText("");
        if (!WHAT.contains(what)) throw new IllegalArgumentException("what must be one of " + String.join(", ", WHAT));
        String id = n.hasNonNull("id") && n.get("id").isTextual() && !n.get("id").asText().isBlank() ? n.get("id").asText() : null;
        if (NEEDS_ID.contains(what) && id == null) throw new IllegalArgumentException(what + " needs an id");
        JsonNode near = n.get("near");
        if (NEEDS_ID.contains(what) && (near == null || !(near.isObject() || near.isTextual())))
            throw new IllegalArgumentException(what + " needs near: a player name or a place");
        boolean area = what.equals("biome") || what.equals("structure");
        int max = what.equals("structure") ? MAX_STRUCTURE_RADIUS : MAX_RADIUS;
        int radius = area ? DEFAULT_AREA_RADIUS : DEFAULT_RADIUS;
        if (n.has("radius")) {
            if (!NEEDS_ID.contains(what)) throw new IllegalArgumentException("radius applies to block, entity, biome and structure only");
            if (!n.get("radius").isInt() || n.get("radius").asInt() < 1) throw new IllegalArgumentException("radius must be a whole number from 1 to " + max);
            radius = n.get("radius").asInt();
            if (radius > max) throw new IllegalArgumentException("radius for " + what + " is at most " + max);
        }
        return new FindSpec(what, id, radius, near);
    }

    /** C4: biome and structure searches are owner-only; a bed only for the asking player or an owner. Null when allowed. */
    static String refusal(FindSpec s, Authority a) {
        switch (s.what()) {
            case "biome", "structure" -> {
                return a.privileged() ? null : "find " + s.what() + " is for owners only.";
            }
            case "player_bed" -> {
                if (a.privileged() || a.players().stream().anyMatch(p -> p.equalsIgnoreCase(String.valueOf(s.id())))) return null;
                return "A player's bed can be looked up only for the player who asked, or by an owner.";
            }
            default -> {
                return null;
            }
        }
    }
}
