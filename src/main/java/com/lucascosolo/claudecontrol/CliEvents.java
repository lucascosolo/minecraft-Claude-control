package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parses one stdout line of {@code claude --output-format stream-json}. Never throws. */
public final class CliEvents {
    public sealed interface Event permits Init, Result, Other {}

    public record Init(String sessionId, String model) implements Event {}

    public record Result(boolean isError, String text, String sessionId, double costUsd, String subtype) implements Event {}

    public record Other(String type) implements Event {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Other INVALID = new Other("invalid");

    private CliEvents() {}

    public static Event parse(String line) {
        JsonNode n;
        try {
            n = MAPPER.readTree(line);
        } catch (Exception e) {
            return INVALID;
        }
        if (n == null || !n.isObject() || !n.path("type").isTextual()) return INVALID;
        String type = n.get("type").asText();
        if (type.equals("system") && "init".equals(n.path("subtype").asText(null))) {
            return new Init(text(n, "session_id"), text(n, "model"));
        }
        if (type.equals("result")) {
            return new Result(n.path("is_error").asBoolean(false), n.path("result").asText(""), text(n, "session_id"),
                    n.path("total_cost_usd").asDouble(0), text(n, "subtype"));
        }
        return new Other(type);
    }

    /** Tool names in an assistant line's tool_use blocks (empty for anything else). Never throws. */
    public static java.util.List<String> toolUses(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            JsonNode n = MAPPER.readTree(line);
            if (n == null || !"assistant".equals(n.path("type").asText(null))) return out;
            for (JsonNode b : n.path("message").path("content")) {
                if ("tool_use".equals(b.path("type").asText(null)) && b.path("name").isTextual()) out.add(b.get("name").asText());
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return out;
    }

    public record ToolCall(String name, String command) {}

    /** Each tool_use block of an assistant line, with input.command when it is a string. Never throws. */
    public static java.util.List<ToolCall> toolCalls(String line) {
        java.util.List<ToolCall> out = new java.util.ArrayList<>();
        try {
            JsonNode n = MAPPER.readTree(line);
            if (n == null || !"assistant".equals(n.path("type").asText(null))) return out;
            for (JsonNode b : n.path("message").path("content")) {
                if ("tool_use".equals(b.path("type").asText(null)) && b.path("name").isTextual()) {
                    JsonNode c = b.path("input").path("command");
                    out.add(new ToolCall(b.get("name").asText(), c.isTextual() ? c.asText() : null));
                }
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.isTextual() ? null : v.asText();
    }
}
