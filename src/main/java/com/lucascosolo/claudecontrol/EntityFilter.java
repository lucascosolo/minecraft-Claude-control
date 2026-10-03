package com.lucascosolo.claudecontrol;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Which entities the entities tool acts on: ids and the groups hostile, animals, items, all_mobs. Players never match. */
final class EntityFilter {
    record Facts(String type, boolean player, boolean hostile, boolean animal, boolean item, boolean mob) {}

    static final Set<String> GROUPS = Set.of("hostile", "animals", "items", "all_mobs");
    private static final Pattern ENTRY = Pattern.compile("[a-z0-9_:]+");
    private final Set<String> ids = new HashSet<>(), groups = new HashSet<>();

    private EntityFilter() {}

    static EntityFilter parse(List<String> types) {
        if (types == null || types.isEmpty()) throw new IllegalArgumentException("types must name at least one entity id or group.");
        if (types.size() > 32) throw new IllegalArgumentException("types may hold at most 32 entries.");
        EntityFilter f = new EntityFilter();
        for (String raw : types) {
            String t = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            if (t.isEmpty() || !ENTRY.matcher(t).matches()) throw new IllegalArgumentException("not an entity id or group: '" + raw + "'");
            if (t.startsWith("minecraft:")) t = t.substring("minecraft:".length());
            if (t.equals("player")) throw new IllegalArgumentException("players cannot be targeted: '" + raw + "'");
            if (GROUPS.contains(t)) f.groups.add(t); else f.ids.add(t);
        }
        return f;
    }

    boolean matches(Facts f) {
        if (f.player()) return false;
        return ids.contains(f.type()) || groups.contains("hostile") && f.hostile() || groups.contains("animals") && f.animal()
                || groups.contains("items") && f.item() || groups.contains("all_mobs") && f.mob();
    }
}
