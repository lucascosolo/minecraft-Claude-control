package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** JSON for tool results, built by hand: the SDK's mapper serialises our records as empty objects. */
public final class ToolJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolJson() {}

    public static String commandResults(List<CommandResult> results) {
        ArrayNode array = MAPPER.createArrayNode();
        for (CommandResult r : results) array.addObject().put("command", r.command()).put("status", r.status()).put("output", r.output());
        return array.toString();
    }

    public static String build(GameTools.Pos origin, int width, int depth, int height, List<GameTools.OpResult> ops,
                               boolean teleported, boolean messageSent, String world) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("origin").put("x", origin.x()).put("y", origin.y()).put("z", origin.z());
        root.putObject("size").put("width", width).put("depth", depth).put("height", height);
        ArrayNode array = root.putArray("ops");
        for (GameTools.OpResult r : ops) {
            ObjectNode o = array.addObject().put("block", r.block());
            if (r.error() == null) o.put("placed", r.placed()); else o.put("error", r.error());
        }
        return root.put("teleported", teleported).put("message_sent", messageSent).put("tip", "places save can name this site, e.g. "
                + "{action: save, name, at: {world: " + world + ", x: " + origin.x() + ", y: " + origin.y() + ", z: " + origin.z() + "}}").toString();
    }
}
