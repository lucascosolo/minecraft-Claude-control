package com.lucascosolo.claudecontrol;

import java.util.Locale;
import java.util.Set;

/** B2: who may change a container. Claude's own containers are free; others need someone nearby (read, add) or an owner (fill, clear). */
final class ContainerGate {
    private static final String NOT_YOURS = "This container was not made by you.";
    private static final String HINT = "Create one with container {action:'fill', create:'chest'} or ask an owner.";

    private ContainerGate() {}

    static String refusal(boolean stamped, String action, Authority a, Set<String> playersWithin6) {
        switch (action) {
            case "read", "add" -> {
                if (stamped || a.privileged()) return null;
                for (String p : a.players()) {
                    for (String n : playersWithin6) if (n.toLowerCase(Locale.ROOT).equals(p.toLowerCase(Locale.ROOT))) return null;
                }
                return NOT_YOURS + " A player who asked must stand within 6 blocks of it. " + HINT;
            }
            case "fill", "clear" -> {
                if (stamped || a.privileged()) return null;
                return NOT_YOURS + " " + HINT;
            }
            default -> throw new IllegalArgumentException("unknown container action '" + action + "'");
        }
    }

    /** C9: a double chest counts as Claude's only when both halves are stamped; otherHalf is null for a single container. */
    static boolean stamped(boolean thisHalf, Boolean otherHalf) {
        return thisHalf && (otherHalf == null || otherHalf);
    }

    /** C9: whether a player's click changes the top (container) inventory, which takes the container off Claude's hands. */
    static boolean playerChange(int rawSlot, int topSize, String action) {
        if (action.equals("MOVE_TO_OTHER_INVENTORY") || action.equals("COLLECT_TO_CURSOR")) return true;
        return rawSlot >= 0 && rawSlot < topSize && !action.equals("NOTHING");
    }
}
