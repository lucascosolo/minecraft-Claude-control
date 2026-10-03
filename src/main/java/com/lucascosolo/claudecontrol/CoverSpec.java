package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Validated arguments of the cover tool (D12). */
record CoverSpec(PlaceExpr at, int radius, List<Weighted> blocks, double density, Set<String> on, int lifetimeSeconds) {
    record Weighted(String id, int weight) {}

    static CoverSpec parse(JsonNode n) throws GameTools.ToolError { return parse(n, Authority.NONE); }

    static CoverSpec parse(JsonNode n, Authority a) throws GameTools.ToolError {
        try {
            return parseSpec(n, a);
        } catch (IllegalArgumentException e) {
            throw new GameTools.ToolError(e.getMessage());
        }
    }

    private static CoverSpec parseSpec(JsonNode n, Authority a) {
        PlaceExpr.obj(n, "cover");
        PlaceExpr.strict(n, "cover", "at", "radius", "blocks", "density", "on", "lifetime_seconds");
        if (!n.has("at")) throw new IllegalArgumentException("at is required");
        PlaceExpr at = PlaceExpr.parse(n.get("at"));
        int radius = PlaceExpr.integer(n, "radius", null, 1, 32);
        double density = PlaceExpr.num(n, "density", null, 0.01, 1);
        List<Weighted> blocks = new ArrayList<>();
        for (JsonNode b : PlaceExpr.array(n, "blocks", 1, 12)) {
            PlaceExpr.obj(b, "block entry");
            PlaceExpr.strict(b, "block entry", "id", "weight");
            String id = PlaceExpr.id(PlaceExpr.text(b, "id", true, 64), "id");
            String refusal = SuiteGates.block(id, a);
            if (refusal != null) throw new IllegalArgumentException(refusal);
            blocks.add(new Weighted(id, PlaceExpr.integer(b, "weight", 1, 1, 100)));
        }
        Set<String> on = new LinkedHashSet<>();
        if (n.has("on")) for (JsonNode o : PlaceExpr.array(n, "on", 0, 32)) on.add(PlaceExpr.id(o.isTextual() ? o.asText() : null, "on"));
        return new CoverSpec(at, radius, List.copyOf(blocks), density, Set.copyOf(on), PlaceExpr.integer(n, "lifetime_seconds", 0, 0, 1800));
    }
}
