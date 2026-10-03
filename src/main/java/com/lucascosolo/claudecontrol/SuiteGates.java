package com.lucascosolo.claudecontrol;

import java.util.Locale;
import java.util.Set;

/** Authority gates for the suite tools: null means allowed, otherwise a short refusal. */
final class SuiteGates {
    static final Set<String> DANGEROUS = Set.of("ender_dragon", "wither", "warden", "tnt", "tnt_minecart", "end_crystal", "fireball",
            "dragon_fireball", "wither_skull", "lightning_bolt", "falling_block", "command_block_minecart", "area_effect_cloud", "evoker_fangs");
    private static final Set<String> RESTRICTED_ITEMS = Set.of("command_block", "chain_command_block", "repeating_command_block",
            "command_block_minecart", "structure_block", "structure_void", "jigsaw", "debug_stick", "light", "barrier", "bedrock",
            "spawner", "trial_spawner");
    private static final Set<String> RESTRICTED_KINDS = Set.of("gamemode", "take", "xp", "set_spawn");

    private SuiteGates() {}

    static String spawn(String type, String nearPlayer, boolean hostile, Authority a) {
        if (DANGEROUS.contains(type) && !(a.privileged() && !a.deferred())) {
            return "Spawning " + type + " is refused: only an operator or owner asking in this turn could allow it.";
        }
        if (!hostile) return null;
        if (nearPlayer != null && !a.asked(nearPlayer)) {
            return "Spawning hostile mobs near " + nearPlayer + " is refused: only " + nearPlayer + " or an operator could allow it.";
        }
        if (nearPlayer == null && a.server()) {
            return "Spawning hostile mobs is refused when no player asked: an operator or owner could allow it.";
        }
        return null;
    }

    static String item(String id, Authority a) {
        if (a.privileged()) return null;
        String i = id.toLowerCase(Locale.ROOT);
        if (RESTRICTED_ITEMS.contains(i) || i.endsWith("_spawn_egg")) {
            return "Giving " + id + " is refused: only an operator or owner could allow it.";
        }
        return null;
    }

    /** B4: moving another player needs an owner; a player of the turn may be moved. */
    static String teleport(String target, Authority a) {
        if (a.privileged() || a.players().stream().anyMatch(p -> p.equalsIgnoreCase(target))) return null;
        return "Moving " + target + " is refused: only that player or an owner can ask for it.";
    }

    static String enchant(String id, int level, int vanillaMax, Authority a) {
        int limit = a.privileged() ? 10 : vanillaMax;
        if (level <= limit) return null;
        return "Enchanting " + id + " at level " + level + " is refused: the limit is " + limit
                + (a.privileged() ? "." : "; only an operator or owner could allow more.");
    }

    static String player(PlayerActions p, Authority a) {
        int stacks = 0;
        for (PlayerActions.Action act : p.actions()) {
            if (act.effect() != null && act.effect().amplifier() > 9 && !a.privileged()) {
                return "Effect amplifiers above 9 are refused: only an operator or owner could allow them.";
            }
            if (act.items() != null) for (ItemSpec s : act.items()) stacks += (s.count() + 63) / 64;
            boolean restricted = RESTRICTED_KINDS.contains(act.kind()) && !("xp".equals(act.kind()) && act.levels() >= 0);
            if (restricted && !a.privileged() && a.players().stream().noneMatch(n -> n.equalsIgnoreCase(p.name()))) {
                return "Action " + act.kind() + " on " + p.name() + " is refused: only " + p.name() + " or an operator could allow it.";
            }
        }
        if (stacks > 36) return "Giving more than 36 stacks at once is refused: only an operator or owner could allow it.";
        return null;
    }
}
