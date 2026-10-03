package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// D5: a batch is privileged only when every player in it is an owner or op.
class AuthorityBatchTest {
    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");
    private static final Set<String> OWNERS = Set.of("Boss", "Boss2");
    private static Trigger chat(String who, boolean op) { return new Trigger("chat", who, op, "hi", T); }
    private static final Trigger WAKE = new Trigger("wake", null, false, "w", T);

    @Test void ownerPlusNonOwnerIsNotPrivileged() {
        assertFalse(Authority.of(List.of(chat("Boss", false), chat("Amy", false)), OWNERS).privileged());
    }

    @Test void twoOwnersArePrivileged() {
        assertTrue(Authority.of(List.of(chat("Boss", false), chat("Boss2", false)), OWNERS).privileged());
    }

    @Test void ownerPlusServerTriggerIsPrivileged() {
        assertTrue(Authority.of(List.of(chat("Boss", false), new Trigger("event", null, false, "e", T)), OWNERS).privileged());
    }

    @Test void onlyServerTriggersAreNotPrivileged() {
        assertFalse(Authority.of(List.of(new Trigger("event", null, false, "e", T)), OWNERS).privileged());
    }

    @Test void opChatWithNonOwnerWakeIsNotPrivileged() {
        assertFalse(Authority.ofTurn(List.of(chat("Opper", true), WAKE), OWNERS, t -> List.of("Sam")).privileged());
    }

    @Test void opChatWithOwnerWakeIsPrivileged() {
        assertTrue(Authority.ofTurn(List.of(chat("Opper", true), WAKE), OWNERS, t -> List.of("Boss")).privileged());
    }

    @Test void ownerChatPlusNonOwnerChatWithWakeIsNotPrivileged() {
        assertFalse(Authority.ofTurn(List.of(chat("Boss", false), chat("Amy", false), WAKE), OWNERS, t -> List.of("Sam")).privileged());
    }
}
