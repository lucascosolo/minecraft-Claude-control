package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** wake: ask for a turn later, on a timer or a condition. {list: true} and {cancel: id} manage them. */
final class WakeTool implements SuiteTool {
    private static final ObjectMapper M = new ObjectMapper();
    private final WakeConditions wakes;

    WakeTool(WakeConditions wakes) { this.wakes = wakes; }

    @Override
    public ToolOutcome run(JsonNode in, Authority a, String actors) throws GameTools.ToolError {
        if (in.path("list").asBoolean(false)) {
            ArrayNode list = M.createArrayNode();
            for (WakeStore.Wake w : wakes.store().list()) {
                list.addObject().put("id", w.id()).put("kind", w.spec().isTimer() ? "timer" : w.spec().type()).put("note", w.spec().note());
            }
            return ToolOutcome.of(M.createObjectNode().set("wakes", list).toString());
        }
        if (in.has("cancel")) {
            if (!wakes.store().cancel(in.path("cancel").asInt(-1))) throw new GameTools.ToolError("no wake with id " + in.path("cancel").asText() + ".");
            return ToolOutcome.of(M.createObjectNode().put("cancelled", in.path("cancel").asInt()).toString());
        }
        WakeSpec spec = WakeSpec.parse(in);
        int id = wakes.add(spec, a);
        return ToolOutcome.of(M.createObjectNode().put("wake", id)
                .put("note", "You will get a turn with source wake when it fires" + (spec.isTimer() ? " in " + spec.afterSeconds() + " s." : ".")).toString());
    }
}
