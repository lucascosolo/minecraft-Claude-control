package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** One running claude process: a stdin writer, a stdout reader thread feeding parsed events, stderr drained to FINE. */
final class CliSession {
    /** Queued once stdout closes; compared by identity. */
    static final CliEvents.Event EOF = new CliEvents.Other("<eof>");
    private static final ObjectMapper M = new ObjectMapper();

    private final Process process;
    private final BufferedWriter stdin;
    private final BlockingQueue<CliEvents.Event> events = new LinkedBlockingQueue<>();
    private final String resumeId;
    private volatile boolean initialized;
    private volatile String stderrTail = "";

    CliSession(List<String> command, Path workDir, Map<String, String> env, String resumeId, Logger log, Consumer<CliEvents.ToolCall> toolUse) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
        pb.environment().remove("ANTHROPIC_API_KEY");
        pb.environment().putAll(env);
        this.process = pb.start();
        this.resumeId = resumeId;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        long pid = process.pid();
        drain("ClaudeControl-cli-out-" + pid, process.getInputStream(), line -> {
            CliEvents.Event e = CliEvents.parse(line);
            if (e instanceof CliEvents.Other o && o.type().equals("assistant")) CliEvents.toolCalls(line).forEach(toolUse);
            log.fine(() -> "claude event: " + e);
            events.add(e);
        }, () -> events.add(EOF));
        drain("ClaudeControl-cli-err-" + pid, process.getErrorStream(), line -> {
            stderrTail = line;
            log.fine(() -> "claude stderr: " + line);
        }, () -> {});
    }

    private static void drain(String name, InputStream in, Consumer<String> line, Runnable atEnd) {
        Thread.ofPlatform().name(name).daemon(true).start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) if (!l.isBlank()) line.accept(l);
            } catch (IOException ignored) {
                // the process is gone
            } finally {
                atEnd.run();
            }
        });
    }

    /** Writes one user turn as a stream-json line. */
    synchronized void send(String text) throws IOException {
        ObjectNode msg = M.createObjectNode().put("type", "user");
        ObjectNode message = msg.putObject("message").put("role", "user");
        message.putArray("content").addObject().put("type", "text").put("text", text);
        stdin.write(M.writeValueAsString(msg));
        stdin.write('\n');
        stdin.flush();
    }

    /** The next event, {@link #EOF} once stdout closed, or null when nothing arrived in time. */
    CliEvents.Event next(long timeoutMs) throws InterruptedException {
        return events.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    void markInitialized() { initialized = true; }

    boolean initialized() { return initialized; }

    String resumeId() { return resumeId; }

    long pid() { return process.pid(); }

    /** Exit code and last stderr line, for error messages; waits briefly for the exit. */
    String exitSummary() {
        try {
            if (process.waitFor(2, TimeUnit.SECONDS)) return "exit code " + process.exitValue() + (stderrTail.isEmpty() ? "" : ", " + stderrTail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "still running" + (stderrTail.isEmpty() ? "" : ", " + stderrTail);
    }

    /** Kills the process and everything it started. */
    void close() {
        List<ProcessHandle> children = process.descendants().toList();
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        children.forEach(ProcessHandle::destroyForcibly);
    }
}
