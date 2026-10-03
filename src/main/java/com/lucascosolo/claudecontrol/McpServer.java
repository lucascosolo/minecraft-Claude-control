package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/** Minimal MCP server over HTTP: JSON-RPC 2.0 on POST /mcp, plain JSON responses, bearer token, loopback only. */
public final class McpServer {
    private static final int MAX_BODY = 1024 * 1024;
    private static final ObjectMapper M = new ObjectMapper();
    private final ToolCatalog catalog;
    private final BiFunction<String, JsonNode, ToolResult> handler;
    private final int requestedPort;
    private final String token;
    private HttpServer server;
    private ExecutorService pool;

    public McpServer(ToolCatalog catalog, BiFunction<String, JsonNode, ToolResult> handler) { this(catalog, handler, 0); }

    public McpServer(ToolCatalog catalog, BiFunction<String, JsonNode, ToolResult> handler, int port) {
        this.catalog = catalog;
        this.handler = handler;
        this.requestedPort = port;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.token = HexFormat.of().formatHex(bytes);
    }

    public synchronized void start() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 0);
        pool = Executors.newVirtualThreadPerTaskExecutor();
        s.setExecutor(pool);
        s.createContext("/mcp", this::handle);
        s.start();
        server = s;
    }

    public synchronized void stop() {
        if (server == null) return;
        server.stop(0);
        pool.shutdownNow();
        server = null;
    }

    public synchronized int port() { return server.getAddress().getPort(); }

    public String token() { return token; }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, null);
                return;
            }
            if (!ex.getRequestMethod().equals("POST")) {
                ex.getResponseHeaders().add("Allow", "POST");
                send(ex, 405, null);
                return;
            }
            byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                send(ex, 413, null);
                return;
            }
            JsonNode req;
            try {
                req = M.readTree(body);
            } catch (IOException e) {
                send(ex, 200, error(NullNode.getInstance(), -32700, "Parse error"));
                return;
            }
            if (req == null || !req.isObject() || !req.path("method").isTextual()) {
                send(ex, 200, error(NullNode.getInstance(), -32600, "Invalid request"));
                return;
            }
            JsonNode id = req.get("id");
            if (id == null) {
                send(ex, 202, null);
                return;
            }
            send(ex, 200, respond(id, req.get("method").asText(), req.path("params")));
        } catch (RuntimeException e) {
            send(ex, 200, error(NullNode.getInstance(), -32603, "Internal error: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private ObjectNode respond(JsonNode id, String method, JsonNode params) {
        ObjectNode result = M.createObjectNode();
        switch (method) {
            case "initialize" -> {
                result.put("protocolVersion", params.path("protocolVersion").asText("2025-06-18"));
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "claudecontrol").put("version", "0.2");
            }
            case "ping" -> {}
            case "tools/list" -> {
                var tools = result.putArray("tools");
                for (ToolCatalog.Tool t : catalog.tools()) {
                    tools.addObject().put("name", t.name()).put("description", t.description()).set("inputSchema", t.inputSchema());
                }
            }
            case "tools/call" -> {
                JsonNode args = params.path("arguments");
                ToolResult r;
                try {
                    r = handler.apply(params.path("name").asText(), args.isObject() ? args : M.createObjectNode());
                } catch (RuntimeException e) {
                    r = new ToolResult("Tool failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), true);
                }
                result.putArray("content").addObject().put("type", "text").put("text", r.text());
                result.put("isError", r.isError());
            }
            default -> { return error(id, -32601, "Method not found: " + method); }
        }
        ObjectNode resp = M.createObjectNode().put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.set("result", result);
        return resp;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode resp = M.createObjectNode().put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.putObject("error").put("code", code).put("message", message);
        return resp;
    }

    private static void send(HttpExchange ex, int status, ObjectNode body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = M.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
