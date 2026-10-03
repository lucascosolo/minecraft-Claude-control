package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContainerGateHintTest {
    private static final String LEAD = "This container was not made by you";

    private static Authority auth(boolean priv, String... players) { return new Authority(List.of(players), priv, players.length == 0, false); }

    private static void assertHint(String r) {
        assertNotNull(r);
        assertTrue(r.startsWith(LEAD), r);
        assertTrue(r.contains("create:'chest'"), r);
        assertTrue(r.contains("owner"), r);
    }

    @Test void readRefusalCarriesHintAndDistance() {
        String r = ContainerGate.refusal(false, "read", auth(false, "Sam"), Set.of("Alex"));
        assertHint(r);
        assertTrue(r.contains("6 blocks"), r);
    }

    @Test void addRefusalCarriesHintAndDistance() {
        String r = ContainerGate.refusal(false, "add", auth(false, "Sam"), Set.of());
        assertHint(r);
        assertTrue(r.contains("6 blocks"), r);
    }

    @Test void fillRefusalCarriesHint() {
        assertHint(ContainerGate.refusal(false, "fill", auth(false, "Sam"), Set.of("Sam")));
    }

    @Test void clearRefusalCarriesHint() {
        assertHint(ContainerGate.refusal(false, "clear", auth(false, "Sam"), Set.of("Sam")));
    }
}
