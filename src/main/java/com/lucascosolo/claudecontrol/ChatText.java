package com.lucascosolo.claudecontrol;

/** Makes chat text safe for every client: Bedrock clients (through Geyser) can drop or garble lines with emoji. */
final class ChatText {
    private ChatText() {}

    /** Removes emoji and other characters outside what both editions render, then tidies the spaces left behind. */
    static String plain(String s) {
        s = s.replaceAll("\\p{Cntrl}", " ");
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp > 0xFFFF) return;                                  // emoji and other astral characters
            if (cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D) return;  // variation selectors, joiner
            if (cp >= 0x2190 && cp <= 0x2BFF) return;                  // arrows, symbols, dingbats (BMP emoji live here)
            if (cp >= 0x3000 && cp <= 0x303F && cp != 0x3000) return;  // CJK symbols used as emoji
            if (cp >= 0xE000 && cp <= 0xF8FF) return;                  // private use (Bedrock glyph icons)
            if (cp == '§') return;                                // section sign: colour codes
            out.appendCodePoint(cp);
        });
        return out.toString().replaceAll("[ \\t]{2,}", " ").replaceAll(" +([.,!?])", "$1").strip();
    }

    /** Like plain, but keeps line breaks, for text where a tool allows several lines (book pages). */
    static String multiline(String s) {
        String[] lines = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) out.append(i == 0 ? "" : "\n").append(plain(lines[i]));
        return out.toString();
    }
}
