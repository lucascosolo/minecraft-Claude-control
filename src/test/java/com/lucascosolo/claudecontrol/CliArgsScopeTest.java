package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class CliArgsScopeTest {
    private static List<String> args(boolean workshop) {
        return CliArgs.build(new CliArgs.Options("claude-bin", "opus", "low", Path.of("/d/prompt.md"), Path.of("/d/mcp.json"),
                null, workshop, List.of("run_commands"), Path.of("/d/agent")));
    }

    /** Tokens after --allowedTools up to the next --flag, split on commas and whitespace. */
    private static Set<String> allowed(List<String> a) {
        int i = a.indexOf("--allowedTools");
        assertTrue(i >= 0);
        Set<String> out = new HashSet<>();
        for (int j = i + 1; j < a.size() && !a.get(j).startsWith("--"); j++) out.addAll(Arrays.asList(a.get(j).split("[,\\s]+")));
        return out;
    }

    @Test void gameModeScopesGlobAndGrepToMemory() {
        Set<String> s = allowed(args(false));
        assertTrue(s.containsAll(Set.of("Glob(memory/**)", "Grep(memory/**)")), s.toString());
        assertFalse(s.contains("Glob"));
        assertFalse(s.contains("Grep"));
        assertFalse(s.contains("Glob(workshop/**)"));
        assertFalse(s.contains("Grep(workshop/**)"));
    }

    @Test void workshopModeAlsoScopesToWorkshop() {
        Set<String> s = allowed(args(true));
        assertTrue(s.containsAll(Set.of("Glob(memory/**)", "Grep(memory/**)", "Glob(workshop/**)", "Grep(workshop/**)")), s.toString());
        assertFalse(s.contains("Glob"));
        assertFalse(s.contains("Grep"));
    }
}
