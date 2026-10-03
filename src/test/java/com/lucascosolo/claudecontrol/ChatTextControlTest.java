package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

// Signatures: ChatText.plain(String) turns every \p{Cntrl} into a space then tidies; static String ChatText.multiline(String)
class ChatTextControlTest {
    @Test void plainTurnsControlCharsIntoSpaces() {
        assertEquals("a b c d e f", ChatText.plain("a\nb\rc\td\u0000e\u007ff"));
    }

    @Test void plainCollapsesAndStrips() {
        assertEquals("a b", ChatText.plain("\n\na\r\n\tb\n"));
    }

    @Test void multilineNormalisesLineEndings() {
        assertEquals("a\nb\nc", ChatText.multiline("a\r\nb\rc"));
    }

    @Test void multilineKeepsNewlinesButStripsOtherControls() {
        assertEquals("a b\nc d", ChatText.multiline("a\tb\nc\u0007d"));
    }

    @Test void multilineTidiesEachLine() {
        assertEquals("a b\nc", ChatText.multiline("  a   b  \n c "));
    }
}
