package com.lucascosolo.claudecontrol;

import java.util.List;

/** activate_plugin and shelve_plugin: any player turn; a server-started turn only for plugins marked safe, and a watch turn may shelve the watched plugin. */
public final class LibraryGate {
    private LibraryGate() {}

    public static boolean allowed(String tool, String plugin, boolean safeForEvents, List<Trigger> turn, String pluginUnderWatch) {
        if (turn.stream().anyMatch(t -> !WorkshopGate.RANDOM_EVENT.equals(t.source()) && !WorkshopGate.PLUGIN_WATCH.equals(t.source()))) return true;
        if (tool.equals("shelve_plugin")) return plugin != null && plugin.equals(pluginUnderWatch);
        return safeForEvents;
    }

    /** C8: only an owner marks a plugin safe for random events, and never one whose catalogue entry says it changes the world. */
    public static String safeForEventsRefusal(boolean owner, boolean changesWorld) {
        if (!owner) return "safe_for_events was ignored: only an owner can set it";
        if (changesWorld) return "safe_for_events was ignored: the plugin changes the world";
        return null;
    }
}
