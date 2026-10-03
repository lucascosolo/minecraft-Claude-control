package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class CliArgsTest {
    private static final List<String> TOOLS = List.of("run_commands", "send_chat", "build");

    private static CliArgs.Options opts(String resume, boolean workshop) {
        return new CliArgs.Options("claude-bin", "opus", "low", Path.of("/d/prompt.md"), Path.of("/d/mcp.json"),
                resume, workshop, TOOLS, Path.of("/d/agent"));
    }

    private static boolean pair(List<String> a, String flag, String value) {
        for (int i = 0; i + 1 < a.size(); i++) if (a.get(i).equals(flag) && a.get(i + 1).equals(value)) return true;
        return false;
    }

    /** Tokens after the flag up to the next --flag, split on commas and whitespace. */
    private static Set<String> values(List<String> a, String flag) {
        int i = a.indexOf(flag);
        assertTrue(i >= 0, flag + " missing in " + a);
        Set<String> out = new HashSet<>();
        for (int j = i + 1; j < a.size() && !a.get(j).startsWith("--"); j++)
            out.addAll(Arrays.asList(a.get(j).split("[,\\s]+")));
        out.remove("");
        return out;
    }

    @Test void commonFlagsInBothModes() {
        for (boolean w : new boolean[] {false, true}) {
            List<String> a = CliArgs.build(opts(null, w));
            assertEquals("claude-bin", a.get(0));
            assertTrue(a.contains("-p"));
            assertTrue(pair(a, "--input-format", "stream-json"));
            assertTrue(pair(a, "--output-format", "stream-json"));
            assertTrue(a.contains("--verbose"));
            assertTrue(a.contains("--strict-mcp-config"));
            assertTrue(pair(a, "--mcp-config", "/d/mcp.json"));
            assertTrue(pair(a, "--system-prompt-file", "/d/prompt.md"));
            assertTrue(pair(a, "--model", "opus"));
            assertTrue(pair(a, "--effort", "low"));
        }
    }

    @Test void resumeOnlyWhenSessionIdGiven() {
        assertFalse(CliArgs.build(opts(null, false)).contains("--resume"));
        assertTrue(pair(CliArgs.build(opts("abc-123", false)), "--resume", "abc-123"));
        assertTrue(pair(CliArgs.build(opts("abc-123", true)), "--resume", "abc-123"));
    }

    @Test void gameModePermissionsAndTools() {
        List<String> a = CliArgs.build(opts(null, false));
        // Amendment 33: file tools are scoped rules and nothing is auto-accepted outside them.
        assertTrue(pair(a, "--permission-mode", "dontAsk"));
        Set<String> allowed = values(a, "--allowedTools");
        for (String t : TOOLS) assertTrue(allowed.contains("mcp__game__" + t), t);
        assertTrue(allowed.containsAll(Set.of("Read(memory/**)", "Write(memory/**)", "Edit(memory/**)", "Glob(memory/**)", "Grep(memory/**)")));   // A6: scoped, was bare Glob and Grep
        for (String bare : List.of("Read", "Write", "Edit")) assertFalse(allowed.contains(bare), bare);
        assertFalse(allowed.contains("Bash"));
        assertTrue(values(a, "--disallowedTools").containsAll(Set.of("Bash", "WebSearch", "Agent")));
        assertFalse(values(a, "--disallowedTools").contains("WebFetch"));
        assertEquals(List.of("WebFetch(domain:minecraft.wiki)"),
                values(a, "--allowedTools").stream().filter(t -> t.startsWith("WebFetch")).toList());
    }

    @Test void workshopModeAllowsBashAndBypassesPermissions() {
        List<String> a = CliArgs.build(opts(null, true));
        // Amendment 33: no permission bypass; workshop adds Bash and the workshop tree.
        assertTrue(pair(a, "--permission-mode", "dontAsk"));
        Set<String> allowed = values(a, "--allowedTools");
        assertTrue(allowed.contains("Bash"));
        for (String t : TOOLS) assertTrue(allowed.contains("mcp__game__" + t), t);
        assertTrue(allowed.containsAll(Set.of("Read(workshop/**)", "Write(workshop/**)", "Edit(workshop/**)", "Read(memory/**)", "Glob(workshop/**)", "Grep(workshop/**)")));   // A6: scoped, was bare Glob and Grep
        if (a.contains("--disallowedTools")) assertFalse(values(a, "--disallowedTools").contains("Bash"));
    }

    // ---- v0.2 amendments 33. Assumed: the scoping shows up as path patterns anywhere in the argument list
    //      (e.g. Read(memory/**) / Edit(memory/**) allowed, Read(/proc/**) denied); only presence is asserted.

    private static String joined(List<String> a) { return String.join(" ", a); }

    @Test void gameModeScopesFileToolsToMemoryAndDeniesSecrets() {
        List<String> a = CliArgs.build(opts(null, false));
        String s = joined(a);
        for (String p : List.of("memory/**", "/proc/**", ".claude/**", "cli-home/**", "/server/**"))
            assertTrue(s.contains(p), p + " missing in " + s);
        assertFalse(s.contains("workshop/**"), s);
        assertFalse(values(a, "--allowedTools").contains("Bash"));
        assertTrue(pair(a, "--setting-sources", "user"));
    }

    @Test void workshopModeAddsWorkshopScopeAndBashAndKeepsDenials() {
        List<String> a = CliArgs.build(opts(null, true));
        String s = joined(a);
        for (String p : List.of("memory/**", "workshop/**", "/proc/**", ".claude/**", "cli-home/**", "/server/**"))
            assertTrue(s.contains(p), p + " missing in " + s);
        assertTrue(values(a, "--allowedTools").contains("Bash"));
        assertTrue(pair(a, "--setting-sources", "user"));
    }

    /** Live bug 2026-10-02: after a Bash cd, relative rules no longer matched and every Write was refused. */
    @Test void scopedRulesAreAlsoAbsoluteSoTheySurviveACd() {
        Set<String> game = values(CliArgs.build(opts(null, false)), "--allowedTools");
        for (String t : List.of("Read", "Edit", "Write", "Glob", "Grep")) assertTrue(game.contains(t + "(//d/agent/memory/**)"), t);
        assertFalse(game.stream().anyMatch(x -> x.contains("/workshop/")));
        Set<String> shop = values(CliArgs.build(opts(null, true)), "--allowedTools");
        for (String t : List.of("Read", "Edit", "Write", "Glob", "Grep")) {
            assertTrue(shop.contains(t + "(//d/agent/workshop/**)"), t);
            assertTrue(shop.contains(t + "(//d/agent/memory/**)"), t);
        }
    }
}
