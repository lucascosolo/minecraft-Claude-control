package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** J2: impersonation is judged on what will be rendered. */
class ChatTextRenderedImpersonationTest {
    private static final List<String> P = List.of("Hannah", ".lucascosolo");

    @Test void invisibleCharactersDoNotHideAName() {
        for (String s : new String[] {"Han‍nah", "Hannah​", "Han‌nah", "Han⁠nah", "﻿Hannah",
                "Hannah️", "Han­nah", " Hannah ", " Hannah　"})
            assertTrue(ChatText.impersonates(s, P), s);
    }

    @Test void formattingCaseAndSpacesIgnored() {
        assertTrue(ChatText.impersonates("§aHannah", P));
        assertTrue(ChatText.impersonates(" hannah ", P));
        assertTrue(ChatText.impersonates("CLAUDE", P));
        assertTrue(ChatText.impersonates(".lucascosolo", P));
    }

    @Test void otherNamesAllowed() {
        for (String s : new String[] {"Hanna", "Snowman", "Batman"}) assertFalse(ChatText.impersonates(s, P), s);
    }

    @Test void agreesWithWhatSayRenders() {
        assertEquals("Hannah", ChatText.plain("Han‍nah"));
        assertTrue(ChatText.impersonates("Han‍nah", P));
        assertEquals(ChatText.plain("Han‍nah").equalsIgnoreCase("Hannah"), ChatText.impersonates("Han‍nah", P));
    }
}
