package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Assumed: LaterStore(Clock clock, Path file) loads file if present; every change is persisted to file as JSON immediately.
//   record LaterStore.Entry(String label, Instant due, int everySeconds, int runsLeft, JsonNode calls)  everySeconds 0 = one-shot
//   void schedule(String label, long afterSeconds, Integer everySeconds, Integer times, JsonNode calls)   IllegalArgumentException on violations
//        (message contains the limit); times counts total runs (>=1), only meaningful with everySeconds; labels are unique
//   List<Entry> list()  pending, soonest first;  boolean cancel(String label)
//   List<Entry> due(Instant now)  entries with due <= now, in time order, consumed: one-shots are removed; a repeat with runs left is
//        rescheduled to due+every (runsLeft-1), otherwise removed
// calls = [{"tool":"spawn","args":{...}}, ...]. Allowed: any suite tool incl. routine and run_commands. Forbidden: workshop tools
// (install_plugin, restart_server, list_plugins, disable_plugin, shelve_plugin, activate_plugin, ...) and later.
// Restart: constructing a store when the clock is past an entry's due: overdue one-shots are still returned (once) by due(), overdue
// repeats are dropped at load.
class LaterStoreTest {
    @TempDir Path dir;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
    private static final JsonNode CALLS = j("[{'tool':'send_chat','args':{'message':'hi'}}]");

    private Path file() { return dir.resolve("later.json"); }
    private LaterStore store() { return new LaterStore(clock, file()); }
    private static List<String> labels(List<LaterStore.Entry> es) { return es.stream().map(LaterStore.Entry::label).toList(); }

    @Test void scheduleListCancel() {
        var s = store();
        s.schedule("b", 20, null, null, CALLS);
        s.schedule("a", 10, null, null, CALLS);
        assertEquals(List.of("a", "b"), labels(s.list()));
        assertEquals(clock.instant().plusSeconds(10), s.list().get(0).due());
        assertTrue(s.cancel("a"));
        assertFalse(s.cancel("a"));
        assertFalse(s.cancel("nope"));
        assertEquals(List.of("b"), labels(s.list()));
    }

    @Test void dueReturnsInTimeOrderAndConsumesOneShots() {
        var s = store();
        s.schedule("late", 30, null, null, CALLS);
        s.schedule("early", 10, null, null, CALLS);
        s.schedule("mid", 20, null, null, CALLS);
        assertEquals(List.of(), s.due(clock.instant().plusSeconds(5)));
        assertEquals(List.of("early"), labels(s.due(clock.instant().plusSeconds(10))));
        assertEquals(List.of("mid", "late"), labels(s.due(clock.instant().plusSeconds(60))));
        assertEquals(List.of(), s.due(clock.instant().plusSeconds(600)));
        assertTrue(s.list().isEmpty());
    }

    @Test void repeatsRunTheGivenNumberOfTimes() {
        var s = store();
        s.schedule("r", 10, 5, 3, CALLS);
        Instant t0 = clock.instant();
        assertEquals(1, s.due(t0.plusSeconds(10)).size());
        assertEquals(t0.plusSeconds(15), s.list().get(0).due());
        assertEquals(List.of(), s.due(t0.plusSeconds(14)));
        assertEquals(1, s.due(t0.plusSeconds(15)).size());
        assertEquals(1, s.due(t0.plusSeconds(20)).size());
        assertEquals(List.of(), s.due(t0.plusSeconds(25)));
        assertTrue(s.list().isEmpty());
    }

    @Test void limitsRejectedAndNamed() {
        var s = store();
        rejects(() -> s.schedule("x", 3601, null, null, CALLS), "3600");
        rejects(() -> s.schedule("x", -1, null, null, CALLS));
        rejects(() -> s.schedule("x", 10, 4, 3, CALLS), "5");
        rejects(() -> s.schedule("x", 10, 5, 61, CALLS), "60");
        rejects(() -> s.schedule("x", 10, 5, 0, CALLS));
        rejects(() -> s.schedule("x", 10, null, null, j("[]")));
        rejects(() -> s.schedule("", 10, null, null, CALLS));
        s.schedule("ok", 3600, 5, 60, CALLS);
        assertEquals(1, s.list().size());
    }

    @Test void atMostTwentyPending() {
        var s = store();
        for (int i = 0; i < 20; i++) s.schedule("l" + i, 10, null, null, CALLS);
        rejects(() -> s.schedule("l20", 10, null, null, CALLS), "20");
        s.cancel("l0");
        s.schedule("l20", 10, null, null, CALLS);
        assertEquals(20, s.list().size());
    }

    @Test void forbiddenInnerToolsRejected() {
        var s = store();
        for (String bad : List.of("install_plugin", "restart_server", "list_plugins", "disable_plugin", "shelve_plugin", "activate_plugin", "later"))
            rejects(() -> s.schedule("x", 10, null, null, j("[{'tool':'" + bad + "','args':{}}]")), bad);
        for (String ok : List.of("routine", "spawn", "show", "player", "run_commands", "send_chat", "build"))
            s.schedule("ok-" + ok, 10, null, null, j("[{'tool':'" + ok + "','args':{}}]"));
        assertEquals(7, s.list().size());
    }

    @Test void jsonRoundTripAcrossRestart() {
        var s = store();
        s.schedule("a", 100, 10, 5, CALLS);
        s.schedule("b", 200, null, null, j("[{'tool':'spawn','args':{'type':'cat','at':{'near':'Sam'}}}]"));
        var before = s.list();
        assertEquals(before, store().list());
    }

    @Test void overdueOneShotsRunOnceAfterRestartAndOverdueRepeatsAreDropped() {
        var s = store();
        s.schedule("once", 30, null, null, CALLS);
        s.schedule("repeat", 30, 10, 5, CALLS);
        s.schedule("future", 3000, null, null, CALLS);
        clock.advance(Duration.ofSeconds(500)); // server was down
        var restarted = store();
        assertEquals(List.of("once"), labels(restarted.due(clock.instant())));
        assertEquals(List.of(), restarted.due(clock.instant()));
        assertEquals(List.of("future"), labels(restarted.list()));
    }
}
