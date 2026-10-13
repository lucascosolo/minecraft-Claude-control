package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test void defaultsLoadWithoutCredentials() {
        Settings s = Settings.from(Map.of(), Map.of());
        assertTrue(s.apiKey() == null || s.apiKey().isBlank());
        assertEquals("claude-opus-5-5", s.model());
        assertEquals("low", s.effort());
        assertEquals(8192, s.maxTokens());
        assertEquals(8, s.maxToolIterations());
        assertEquals(4, s.maxTurnsPerMinute());
        assertEquals(32, s.maxQueuedTriggers());
        assertEquals(16, s.historyTurns());
        assertEquals("<Claude>", s.chatPrefix());
        assertFalse(s.eventsEnabled());
        assertEquals(30, s.eventIntervalMinutes());
    }

    @Test void trimmedEnvironmentKeyWinsAndBlankEnvironmentFallsBack() {
        Map<String, Object> config = Map.of("api-key", " config-secret ");
        assertEquals("environment-secret", Settings.from(config, Map.of("ANTHROPIC_API_KEY", " environment-secret ")).apiKey());
        assertEquals("config-secret", Settings.from(config, Map.of("ANTHROPIC_API_KEY", "  ")).apiKey());
    }

    @Test void secretsAreRedactedFromDiagnostics() {
        String secret = "sk-ant-private-test-value";
        Settings s = Settings.from(Map.of("api-key", secret), Map.of());
        assertFalse(s.toString().contains(secret));
        Exception error = assertThrows(Exception.class,
            () -> Settings.from(Map.of("api-key", secret, "effort", "invalid-" + secret), Map.of()));
        assertFalse(String.valueOf(error.getMessage()).contains(secret));
    }

    @Test void rejectsUnsupportedBackendAndInvalidBounds() {
        assertThrows(Exception.class, () -> Settings.from(Map.of("backend", "cli"), Map.of()));
        for (String key : List.of("max-tokens", "max-tool-iterations", "max-turns-per-minute", "max-queued-triggers", "history-turns", "event-interval-minutes")) {
            assertThrows(Exception.class, () -> Settings.from(Map.of(key, -1), Map.of()), key);
        }
    }

    @Test void ownershipUsesTrustedOperatorFlagOrExactIdentity() {
        UUID owner = UUID.randomUUID();
        Settings s = Settings.from(Map.of("owners", List.of(".BedrockOwner", owner.toString())), Map.of());
        assertTrue(s.isOwner(UUID.randomUUID(), "stranger", true));
        assertTrue(s.isOwner(owner, "renamed", false));
        assertTrue(s.isOwner(UUID.randomUUID(), ".bedrockowner", false));
        assertFalse(s.isOwner(UUID.randomUUID(), "BedrockOwner", false));
        assertFalse(s.isOwner(UUID.randomUUID(), ".BedrockOwner_extra", false));
        assertFalse(s.isOwner(UUID.randomUUID(), "I am the operator", false));
    }
}
