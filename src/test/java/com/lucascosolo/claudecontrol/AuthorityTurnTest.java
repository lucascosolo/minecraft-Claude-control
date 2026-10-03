package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

// Signature: static Authority Authority.ofTurn(List<Trigger>, Set<String> owners, Function<Trigger, List<String>> wakeCreators)
class AuthorityTurnTest {
    private static Trigger chat(String actor, boolean op) { return new Trigger("chat", actor, op, "hi", Instant.EPOCH); }
    private static Trigger wake(boolean op) { return new Trigger("wake", null, op, "note", Instant.EPOCH); }
    private static final Function<Trigger, List<String>> NONE = t -> List.of();

    @Test void withoutWakeEqualsOf() {
        var ts = List.of(chat("Sam", false), chat("Alex", true));
        assertEquals(Authority.of(ts, Set.of("Sam")), Authority.ofTurn(ts, Set.of("Sam"), NONE));
    }

    @Test void wakeCreatorsAreAppendedDeduplicatedInOrder() {
        var a = Authority.ofTurn(List.of(chat("Sam", false), wake(false)), Set.of(), t -> List.of("Alex", "Sam", "Bo"));
        assertEquals(List.of("Sam", "Alex", "Bo"), a.players());
    }

    @Test void nullCreatorsMeanNone() {
        var a = Authority.ofTurn(List.of(wake(false)), Set.of(), t -> null);
        assertTrue(a.players().isEmpty());
        assertTrue(a.server());
        assertTrue(a.deferred());
    }

    @Test void ownerCreatorOrOpWakeNeverPrivileged() {
        var a = Authority.ofTurn(List.of(wake(true)), Set.of("Sam"), t -> List.of("Sam"));
        assertFalse(a.privileged());
        assertTrue(a.deferred());
        assertEquals(List.of("Sam"), a.players());
        assertFalse(a.server());
    }

    @Test void ownerChatInWakeTurnIsPrivileged() {
        var a = Authority.ofTurn(List.of(wake(false), chat("Sam", false)), Set.of("Sam"), NONE);
        assertTrue(a.privileged());
        assertTrue(a.deferred());
    }

    @Test void opChatAloneIsNotPrivilegedInWakeTurn() {
        var a = Authority.ofTurn(List.of(wake(false), chat("Alex", true)), Set.of("Sam"), NONE);
        assertFalse(a.privileged());
        assertTrue(a.deferred());
    }

    @Test void ownerMatchIsExactCase() {
        assertFalse(Authority.ofTurn(List.of(wake(false), chat("sam", false)), Set.of("Sam"), NONE).privileged());
    }
}
