package com.lucascosolo.claudecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** A written book (given to a player or put in a container) or a sign's text. */
record BookSpec(String toPlayer, PlaceExpr container, String title, String author, List<String> pages, PlaceExpr signAt, List<String> signLines) {
    boolean isSign() { return signAt != null; }

    static BookSpec parse(JsonNode n) {
        PlaceExpr.obj(n, "book");
        if (n.has("to") == n.has("sign")) throw new IllegalArgumentException("book needs exactly one of to or sign");
        if (n.has("sign")) {
            PlaceExpr.strict(n, "book", "sign");
            JsonNode s = PlaceExpr.obj(n.get("sign"), "sign");
            PlaceExpr.strict(s, "sign", "at", "lines");
            PlaceExpr at = absolute(s, "at");
            return new BookSpec(null, null, null, null, List.of(), at, strings(s, "lines", 1, 4, 15, false));
        }
        PlaceExpr.strict(n, "book", "to", "title", "author", "pages");
        String player = null;
        PlaceExpr container = null;
        JsonNode to = n.get("to");
        if (to.isObject()) {
            PlaceExpr.strict(to, "to", "container");
            container = absolute(to, "container");
        } else {
            player = PlaceExpr.player(to, "to");
            if (player.startsWith("$")) throw new IllegalArgumentException("to must be a player name (no selectors)");
        }
        String title = ChatText.plain(PlaceExpr.text(n, "title", true, 32));
        String author = n.has("author") ? ChatText.plain(PlaceExpr.text(n, "author", true, 16)) : null;
        return new BookSpec(player, container, title, author, strings(n, "pages", 1, 20, 256, true), null, List.of());
    }

    private static PlaceExpr absolute(JsonNode o, String key) {
        if (!o.has(key)) throw new IllegalArgumentException(key + " is required");
        PlaceExpr p = PlaceExpr.parse(o.get(key));
        if (p.absolute() == null) throw new IllegalArgumentException(key + " must be an absolute place {world,x,y,z}");
        return p;
    }

    private static List<String> strings(JsonNode o, String key, int min, int max, int maxLen, boolean lines) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : PlaceExpr.array(o, key, min, max)) {
            if (!v.isTextual() || v.asText().length() > maxLen)
                throw new IllegalArgumentException(key + " entries must be text of at most " + maxLen + " characters");
            out.add(lines ? ChatText.multiline(v.asText()) : ChatText.plain(v.asText()));
        }
        return List.copyOf(out);
    }
}
