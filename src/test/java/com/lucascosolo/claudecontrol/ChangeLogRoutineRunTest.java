package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// E8: routine merging and the per-run cap key on the run, not the actors label.
class ChangeLogRoutineRunTest {
    @TempDir Path dir;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
    private Path file() { return dir.resolve("changes.json"); }
    private ChangeLog log() { return new ChangeLog(100, clock, file(), 2000); }
    private static List<ChangeLog.Change> beat(int i) { return List.of(new ChangeLog.BlockChange("world", i, 64, 0, "stone", "air")); }
    private static ChangeLog.Result app(ChangeLog log, String run, int i) { return log.append("routine", "lbl", beat(i), List.of(), "routine", run); }
    private static void fill(ChangeLog log, String run) {
        boolean full = false;
        for (int i = 0; i < 400 && !full; i++) full = app(log, run, i).notRecorded();
        assertTrue(full);
    }
    private static long count(ChangeLog log) { return log.recent(100).stream().filter(e -> e.actors().equals("lbl")).count(); }

    @Test void aFullRunDoesNotBlockTheNextRunWithTheSameLabel() {
        var log = log();
        List<String> warns = new ArrayList<>();
        log.warn = warns::add;
        fill(log, "r1");
        assertEquals(1, warns.size());
        assertFalse(app(log, "r2", 0).notRecorded());
        fill(log, "r2");
        assertEquals(2, warns.size());
    }

    @Test void twoRunsNeverMergeIntoOneEntry() {
        var log = log();
        app(log, "r1", 0);
        app(log, "r2", 1);
        assertEquals(2, count(log));
    }

    @Test void fiveArgFormBehavesAsRunEqualsActors() {
        var log = log();
        log.append("routine", "lbl", beat(0), List.of(), "routine");
        log.append("routine", "lbl", beat(1), List.of(), "routine", "lbl");
        assertEquals(1, count(log));
    }

    @Test void aMissingSizeAfterReloadNeverMerges() {
        var log = log();
        app(log, "r1", 0);
        var again = new ChangeLog(100, clock, file(), 2000);
        assertEquals(1, count(again));
        app(again, "r1", 1);
        assertEquals(2, count(again));
    }
}
