package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Who asked for a turn's actions and whether any of them may do privileged things. */
public record Authority(List<String> players, boolean privileged, boolean server, boolean deferred) {
    public static final Authority NONE = new Authority(List.of(), false, true, true);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static Authority of(List<Trigger> triggers, Set<String> owners) {
        List<String> players = new ArrayList<>();
        boolean privileged = false;
        for (Trigger t : triggers) {
            if (t.actor() == null) continue;
            if (!players.contains(t.actor())) players.add(t.actor());
            if (t.actorIsOp() || owners.contains(t.actor())) privileged = true;
        }
        return new Authority(List.copyOf(players), privileged, players.isEmpty(), false);
    }

    /**
     * B1: a turn with a wake trigger is deferred; the wake's creators count as players who asked but never give privilege,
     * which then comes only from an owner's own chat line in the batch.
     */
    public static Authority ofTurn(List<Trigger> triggers, Set<String> owners, java.util.function.Function<Trigger, List<String>> wakeCreators) {
        if (triggers.stream().noneMatch(t -> "wake".equals(t.source()))) return of(triggers, owners);
        List<String> players = new ArrayList<>();
        boolean privileged = false;
        for (Trigger t : triggers) {
            List<String> names = "wake".equals(t.source()) ? wakeCreators.apply(t) : (t.actor() == null ? List.of() : List.of(t.actor()));
            if (names != null) for (String n : names) if (!players.contains(n)) players.add(n);
            if ("chat".equals(t.source()) && t.actor() != null && owners.contains(t.actor())) privileged = true;
        }
        return new Authority(List.copyOf(players), privileged, players.isEmpty(), true);
    }

    public Authority asDeferred() { return new Authority(players, privileged, server, true); }

    public boolean asked(String player) {
        return privileged || players.stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).equals(String.valueOf(player).toLowerCase(Locale.ROOT)));
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(java.util.Map.of("players", players, "privileged", privileged, "server", server, "deferred", deferred));
        } catch (Exception e) {
            return "{}";
        }
    }

    public static Authority fromJson(String json) {
        if (json == null) return NONE;
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || !n.isObject() || !n.get("players").isArray()) return NONE;
            List<String> players = new ArrayList<>();
            n.get("players").forEach(p -> players.add(p.asText()));
            return new Authority(List.copyOf(players), n.get("privileged").asBoolean(), n.get("server").asBoolean(), n.get("deferred").asBoolean());
        } catch (Exception e) {
            return NONE;
        }
    }
}
