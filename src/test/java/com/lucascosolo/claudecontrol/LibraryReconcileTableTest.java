package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

// D10: PluginLibrary.placement(inPlugins, inLibrary, state, entrySha, libSha, pluginsSha) follows the shared table in /library-reconcile-cases.txt.
class LibraryReconcileTableTest {
    @TempDir Path dir;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
    private static final Set<String> NO = Set.of();

    @TestFactory List<DynamicTest> everyRowOfTheSharedTable() throws IOException {
        List<String> rows;
        try (var in = LibraryReconcileTableTest.class.getResourceAsStream("/library-reconcile-cases.txt")) {
            assertNotNull(in, "fixture missing");
            rows = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        }
        assertTrue(rows.size() >= 8, "rows read: " + rows.size());
        List<DynamicTest> tests = new ArrayList<>();
        for (String row : rows) {
            String[] c = row.split("\\|");
            tests.add(DynamicTest.dynamicTest(c[0], () -> {
                boolean mismatch = c[3].equals("mismatch");
                String state = c[3].equals("none") ? "shelved" : "activating";
                var expected = switch (c[4]) {
                    case "none" -> PluginLibrary.Placement.NONE;
                    case "library" -> PluginLibrary.Placement.KEEP_LIBRARY;
                    case "activated", "activated-older" -> PluginLibrary.Placement.COMPLETE_ACTIVATION;
                    default -> throw new IllegalStateException(c[4]);
                };
                assertEquals(expected, PluginLibrary.placement(c[1].equals("yes"), c[2].equals("yes"), state, mismatch ? "bbb" : "aaa", "aaa", c[3].equals("plugins-differs") ? "ccc" : "aaa"));
            }));
        }
        return tests;
    }

    private PluginLibrary libWith(String state) {
        var l = new PluginLibrary(clock, dir.resolve("plugin-library.json"));
        l.add("X", "p", "u", false, true, "Sam");
        l.shelve("X", "aaa");
        if (state.equals("activating")) l.begin("X", "activating");
        return l;
    }

    @Test void shelvedEntryWithJarInBothFoldersStaysShelved() {
        var l = libWith("shelved");
        l.reconcile(new PluginLibrary.Folders(Set.of("X"), Map.of("X", "aaa"), NO, NO));
        var e = l.get("X").orElseThrow();
        assertEquals("shelved", e.state());
        assertEquals("aaa", e.sha256());
    }

    @Test void activatingEntryWithJarInBothFoldersBecomesActive() {
        var l = libWith("activating");
        l.reconcile(new PluginLibrary.Folders(Set.of("X"), Map.of("X", "aaa"), NO, NO));
        var e = l.get("X").orElseThrow();
        assertEquals("active", e.state());
        assertNotNull(e.until());
    }
}
