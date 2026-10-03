package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// Assumed signature:
//   static boolean WorkshopGate.allowed(String tool, String targetPlugin, List<Trigger> turnTriggers, String pluginUnderWatch)
// tool is "install_plugin" or "restart_server"; targetPlugin is the install target (null for restart_server);
// pluginUnderWatch is the plugin named by the plugin-watch trigger (null when none).
class WorkshopGateTest {
    private static Trigger trig(String source, String actor) {
        return new Trigger(source, actor, false, "x", Instant.EPOCH);
    }

    private static final Trigger RANDOM = trig("random-event", null);
    private static final Trigger CHAT = trig("chat", "Sam");
    private static final Trigger WATCH = trig("plugin-watch", null);

    @Test void allRandomEventRefusesBothTools() {
        var batch = List.of(RANDOM, RANDOM);
        assertFalse(WorkshopGate.allowed("install_plugin", "ClaudeFun", batch, null));
        assertFalse(WorkshopGate.allowed("restart_server", null, batch, null));
    }

    @Test void anyChatTriggerAllowsBothTools() {
        var batch = List.of(RANDOM, CHAT);
        assertTrue(WorkshopGate.allowed("install_plugin", "ClaudeFun", batch, null));
        assertTrue(WorkshopGate.allowed("restart_server", null, batch, null));
        assertTrue(WorkshopGate.allowed("install_plugin", "ClaudeFun", List.of(CHAT), null));
    }

    @Test void watchOnlyTurnAllowsInstallOnlyForPluginUnderWatch() {
        var batch = List.of(WATCH);
        assertTrue(WorkshopGate.allowed("install_plugin", "ClaudeFun", batch, "ClaudeFun"));
        assertFalse(WorkshopGate.allowed("install_plugin", "ClaudeOther", batch, "ClaudeFun"));
        assertFalse(WorkshopGate.allowed("install_plugin", "ClaudeFun", batch, null));
    }

    @Test void watchOnlyTurnMayNotRestart() {   // A8 (was: watchOnlyTurnAllowsRestart)
        assertFalse(WorkshopGate.allowed("restart_server", null, List.of(WATCH), "ClaudeFun"));
    }

    @Test void watchPlusChatIsNotRestrictedToWatchedPlugin() {
        assertTrue(WorkshopGate.allowed("install_plugin", "ClaudeNew", List.of(WATCH, CHAT), "ClaudeFun"));
    }
}
