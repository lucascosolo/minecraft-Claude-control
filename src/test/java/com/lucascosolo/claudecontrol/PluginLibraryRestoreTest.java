package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// D2: restore(Entry) puts an entry back verbatim; activateAndDrop records the activation and only then deletes the stored jar.
class PluginLibraryRestoreTest {
    @TempDir Path dir;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
    private Path file() { return dir.resolve("plugin-library.json"); }
    private PluginLibrary lib() { return new PluginLibrary(clock, file()); }
    private static void add(PluginLibrary l, String n) { l.add(n, "does " + n, "/" + n, false, true, "Sam"); }

    @Test void restorePutsTheEntryBackVerbatimAndPersists() {
        var l = lib();
        add(l, "X");
        l.setLifetime("X", "60");
        var prev = l.get("X").orElseThrow();
        l.begin("X", "shelving");
        l.restore(prev);
        var e = l.get("X").orElseThrow();
        assertEquals("active", e.state());
        assertEquals(prev.until(), e.until());
        clock.advance(Duration.ofSeconds(61));
        assertTrue(l.expired(clock.instant()).stream().anyMatch(x -> x.name().equals("X")));
        assertEquals("active", lib().get("X").orElseThrow().state());
    }

    @Test void activateAndDropKeepsTheStoredJarWhenActivateFails() throws IOException {
        var l = lib();
        add(l, "X");
        l.shelve("X", "abc");
        Path stored = Files.writeString(dir.resolve("X.jar"), "jar");
        assertThrows(IllegalArgumentException.class, () -> l.activateAndDrop("X", "wrong", null, stored));
        assertTrue(Files.exists(stored));
        assertEquals("shelved", l.get("X").orElseThrow().state());
    }

    @Test void activateAndDropRecordsThenDeletesTheStoredJar() throws IOException {
        var l = lib();
        add(l, "X");
        l.shelve("X", "abc");
        Path stored = Files.writeString(dir.resolve("X.jar"), "jar");
        var e = l.activateAndDrop("X", "abc", null, stored);
        assertEquals("active", e.state());
        assertEquals("active", l.get("X").orElseThrow().state());
        assertFalse(Files.exists(stored));
    }
}
