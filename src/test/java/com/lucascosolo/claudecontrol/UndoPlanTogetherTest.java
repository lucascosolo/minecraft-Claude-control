package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

// C6: an entry's block and container/sign changes at one key are judged together.
class UndoPlanTogetherTest {
    private static final String K = UndoPlan.key("world", 1, 64, 0);
    private static final ChangeLog.BlockChange CHEST = new ChangeLog.BlockChange("world", 1, 64, 0, "air", "chest");
    private static final ChangeLog.ContainerChange FILL = new ChangeLog.ContainerChange("world", 1, 64, 0, "single",
            Arrays.asList(null, null), Arrays.asList("A", null));
    private static final ChangeLog.BlockChange SIGN = new ChangeLog.BlockChange("world", 1, 64, 0, "air", "oak_sign");
    private static final List<String> BLANK = List.of("", "", "", "");
    private static final ChangeLog.SignChange TEXT = new ChangeLog.SignChange("world", 1, 64, 0, BLANK, List.of("hi", "", "", ""));

    private static ChangeLog.Entry entry(ChangeLog.Change... cs) {
        var log = new ChangeLog(20, new MutableClock(Instant.EPOCH));
        log.append("build", "Sam", List.of(cs));
        return log.recent(1).get(0);
    }

    private static UndoPlan.State chest(List<String> contents) {
        return new UndoPlan.State(Map.of(K, "chest"), Map.of(K, contents), Map.of(), Set.of());
    }

    private static UndoPlan.State sign(List<String> lines) {
        return new UndoPlan.State(Map.of(K, "oak_sign"), Map.of(), Map.of(K, lines), Set.of());
    }

    @Test void playerAddedItemSkipsBlockAndContainerTogether() {
        var p = UndoPlan.plan(entry(CHEST, FILL), chest(Arrays.asList("A", "B")));
        assertTrue(p.restore().isEmpty());
        assertEquals(2, p.skipped().size());
        assertTrue(p.skipped().stream().anyMatch(s -> s.change().equals(CHEST) && s.reason().contains("items")));
        assertTrue(p.skipped().stream().anyMatch(s -> s.change().equals(FILL)));
    }

    @Test void untouchedContentsRestoreContainerThenBlock() {
        var p = UndoPlan.plan(entry(CHEST, FILL), chest(Arrays.asList("A", null)));
        assertEquals(List.of(FILL, CHEST), p.restore());
        assertTrue(p.skipped().isEmpty());
    }

    @Test void blockOnlyChestRestoresWhenEmpty() {
        assertEquals(List.of(CHEST), UndoPlan.plan(entry(CHEST), chest(Arrays.asList(null, null))).restore());
        assertEquals(List.of(CHEST), UndoPlan.plan(entry(CHEST), chest(List.of())).restore());
    }

    @Test void blockOnlyChestWithItemsSkippedForItems() {
        var p = UndoPlan.plan(entry(CHEST), chest(Arrays.asList("X", null)));
        assertTrue(p.restore().isEmpty());
        assertTrue(p.skipped().get(0).reason().contains("items"));
    }

    @Test void signEditedByPlayerSkipsBlockAndSignChange() {
        var p = UndoPlan.plan(entry(SIGN, TEXT), sign(List.of("changed", "", "", "")));
        assertTrue(p.restore().isEmpty());
        assertEquals(2, p.skipped().size());
    }

    @Test void signAsClaudeLeftItRestores() {
        var p = UndoPlan.plan(entry(SIGN, TEXT), sign(List.of("hi", "", "", "")));
        assertEquals(List.of(TEXT, SIGN), p.restore());
    }

    @Test void signBlockOnlyNeedsBlankLines() {
        assertEquals(List.of(SIGN), UndoPlan.plan(entry(SIGN), sign(BLANK)).restore());
        assertTrue(UndoPlan.plan(entry(SIGN), sign(List.of("x", "", "", ""))).restore().isEmpty());
    }

    @Test void changedBlockStillSkippedAsChangedSince() {
        var st = new UndoPlan.State(Map.of(K, "stone"), Map.of(), Map.of(), Set.of());
        var p = UndoPlan.plan(entry(CHEST), st);
        assertTrue(p.skipped().get(0).reason().contains("changed since"));
    }
}
