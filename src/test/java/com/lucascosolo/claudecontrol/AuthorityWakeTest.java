package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// E5: a wake created by a non-owner non-op stops privilege; null-actor triggers are neutral.
class AuthorityWakeTest {
    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");
    private static final Set<String> OWNERS = Set.of("Boss");
    private static final Trigger OWNER_CHAT = new Trigger("chat", "Boss", false, "hi", T);
    private static final Trigger WAKE = new Trigger("wake", null, false, "w", T);
    private static final Trigger WATCH = new Trigger("plugin-watch", null, false, "p", T);

    @Test void wakeFromNonOwnerStopsPrivilegeEvenWithOwnerChat() {
        var a = Authority.ofTurn(List.of(WAKE, OWNER_CHAT), OWNERS, t -> List.of("Boss", "Mallory"), n -> false);
        assertFalse(a.privileged());
        assertTrue(a.deferred());
    }

    @Test void wakeFromOwnerPlusOwnerChatIsPrivileged() {
        assertTrue(Authority.ofTurn(List.of(WAKE, OWNER_CHAT), OWNERS, t -> List.of("Boss"), n -> false).privileged());
    }

    @Test void wakeFromOwnerAloneIsNot() {
        assertFalse(Authority.ofTurn(List.of(WAKE), OWNERS, t -> List.of("Boss"), n -> false).privileged());
    }

    @Test void opCreatorCountsAsTrusted() {
        assertTrue(Authority.ofTurn(List.of(WAKE, OWNER_CHAT), OWNERS, t -> List.of("Opie"), n -> n.equals("Opie")).privileged());
        assertFalse(Authority.ofTurn(List.of(WAKE, OWNER_CHAT), OWNERS, t -> List.of("Opie"), n -> false).privileged());
    }

    @Test void threeArgOverloadTreatsNobodyAsOp() {
        assertFalse(Authority.ofTurn(List.of(WAKE, OWNER_CHAT), OWNERS, t -> List.of("Opie")).privileged());
    }

    @Test void nullActorTriggersAreNeutral() {
        assertTrue(Authority.of(List.of(OWNER_CHAT, WATCH), OWNERS).privileged());
        assertTrue(Authority.ofTurn(List.of(OWNER_CHAT, WATCH), OWNERS, t -> List.of()).privileged());
        assertTrue(Authority.of(List.of(OWNER_CHAT, new Trigger("random-event", null, false, "r", T)), OWNERS).privileged());
        assertFalse(Authority.of(List.of(WATCH), OWNERS).privileged());
        assertFalse(Authority.ofTurn(List.of(WATCH), OWNERS, t -> List.of()).privileged());
    }
}
