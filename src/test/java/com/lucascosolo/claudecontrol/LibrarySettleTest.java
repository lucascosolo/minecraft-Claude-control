package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// E3: LibrarySettle.settle finishes or rolls back a half-done activation, keyed on the marker sha and the plugins/ jar.
class LibrarySettleTest {
    @TempDir Path dir;
    Path plugins, lib, quarantineDir;
    final List<Path> quarantined = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final Map<String, String> library = new HashMap<>();
    final Map<String, String> markers = new HashMap<>();

    @BeforeEach void setUp() throws IOException {
        plugins = Files.createDirectories(dir.resolve("plugins"));
        lib = Files.createDirectories(dir.resolve("claude-library"));
        quarantineDir = Files.createDirectories(dir.resolve("quarantine"));
    }

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private Path put(Path folder, String file, String content) throws IOException { return Files.write(folder.resolve(file), b(content)); }

    private void libraryJar(String base, String content) throws IOException {
        put(lib, base + ".jar", content);
        library.put(base, LibrarySettle.sha256(b(content)));
    }

    private static String baseOf(Path jar) {
        String n = jar.getFileName().toString();
        if (!n.startsWith("Claude")) return null;
        n = n.substring(0, n.length() - 4);
        int i = n.indexOf("_r");
        return i < 0 ? n : n.substring(0, i);
    }

    private void settle(LibrarySettle.Quarantine q) {
        LibrarySettle.settle(plugins, lib, library, markers::get, LibrarySettleTest::baseOf, q, warnings::add);
    }

    private void settle() {
        settle(jar -> { quarantined.add(jar); Files.move(jar, quarantineDir.resolve(jar.getFileName())); });
    }

    @Test void sha256IsLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LibrarySettle.sha256(b("abc")));
    }

    @Test void matchingMarkerCompletesActivationAndDropsLibraryCopy() throws IOException {
        libraryJar("ClaudeX", "full jar");
        put(plugins, "ClaudeX.jar", "full jar");
        markers.put("ClaudeX", library.get("ClaudeX"));
        settle();
        assertFalse(Files.exists(lib.resolve("ClaudeX.jar")));
        assertFalse(library.containsKey("ClaudeX"));
        assertTrue(Files.exists(plugins.resolve("ClaudeX.jar")));
        assertTrue(quarantined.isEmpty());
    }

    @Test void markerMatchingAnyOfSeveralPluginsJarsCompletes() throws IOException {
        libraryJar("ClaudeX", "full jar");
        put(plugins, "ClaudeX.jar", "trunc");
        put(plugins, "ClaudeX_r2.jar", "full jar");
        markers.put("ClaudeX", library.get("ClaudeX"));
        settle();
        assertFalse(Files.exists(lib.resolve("ClaudeX.jar")));
        assertFalse(library.containsKey("ClaudeX"));
        assertFalse(Files.exists(plugins.resolve("ClaudeX.jar")));
        assertTrue(Files.exists(plugins.resolve("ClaudeX_r2.jar")));
        assertEquals(List.of(plugins.resolve("ClaudeX.jar")), quarantined);
    }

    @Test void truncatedPluginsJarIsQuarantinedAndLibraryCopyKept() throws IOException {
        libraryJar("ClaudeX", "full jar");
        Path trunc = put(plugins, "ClaudeX.jar", "full");
        markers.put("ClaudeX", library.get("ClaudeX"));
        settle();
        assertEquals(List.of(trunc), quarantined);
        assertTrue(Files.exists(quarantineDir.resolve("ClaudeX.jar")));
        assertFalse(Files.exists(trunc));
        assertTrue(Files.exists(lib.resolve("ClaudeX.jar")));
        assertTrue(library.containsKey("ClaudeX"));
    }

    @Test void noMarkerQuarantinesEveryPluginsJarOfTheBase() throws IOException {
        libraryJar("ClaudeX", "full jar");
        put(plugins, "ClaudeX.jar", "full jar");
        put(plugins, "ClaudeX_r2.jar", "full jar");
        settle();
        assertEquals(2, quarantined.size());
        assertTrue(Files.exists(lib.resolve("ClaudeX.jar")));
        assertTrue(library.containsKey("ClaudeX"));
    }

    @Test void markerWithAnotherShaIsNotACompletion() throws IOException {
        libraryJar("ClaudeX", "full jar");
        put(plugins, "ClaudeX.jar", "full jar");
        markers.put("ClaudeX", LibrarySettle.sha256(b("other")));
        settle();
        assertEquals(1, quarantined.size());
        assertTrue(Files.exists(lib.resolve("ClaudeX.jar")));
    }

    @Test void quarantineFailureIsWarnedAndOtherBasesStillSettle() throws IOException {
        libraryJar("ClaudeA", "a full");
        libraryJar("ClaudeB", "b full");
        put(plugins, "ClaudeA.jar", "a");
        put(plugins, "ClaudeB.jar", "b full");
        markers.put("ClaudeB", library.get("ClaudeB"));
        settle(jar -> { throw new IOException("disk says no"); });
        assertFalse(warnings.isEmpty());
        assertFalse(library.containsKey("ClaudeB"));
        assertFalse(Files.exists(lib.resolve("ClaudeB.jar")));
        assertTrue(Files.exists(lib.resolve("ClaudeA.jar")));
    }

    @Test void unrelatedJarIsUntouched() throws IOException {
        libraryJar("ClaudeX", "full jar");
        Path other = put(plugins, "Essentials.jar", "not ours");
        settle();
        assertTrue(Files.exists(other));
        assertTrue(quarantined.isEmpty());
        assertTrue(Files.exists(lib.resolve("ClaudeX.jar")));
    }

    // H10: the older jar of the same base (Base.jar next to Base_r2.jar) goes to quarantine when the newer revision completes the activation.
    @Test void olderBaseJarMovesWhenTheRevisionCompletesTheActivation() throws IOException {
        libraryJar("Base", "new content");
        Path older = put(plugins, "Base.jar", "older content");
        put(plugins, "Base_r2.jar", "new content");
        markers.put("Base", library.get("Base"));
        LibrarySettle.settle(plugins, lib, library, markers::get, jar -> jar.getFileName().toString().startsWith("Base") ? "Base" : null,
                jar -> { quarantined.add(jar); Files.move(jar, quarantineDir.resolve(jar.getFileName())); }, warnings::add);
        assertEquals(List.of(older), quarantined);
        assertTrue(Files.exists(plugins.resolve("Base_r2.jar")));
        assertFalse(Files.exists(lib.resolve("Base.jar")));
    }
}
