package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatTextTest {
    @Test void removesEmojiAndKeepsTheSentence() {
        assertEquals("Good morning! Sun's up.", ChatText.plain("Good morning! ☀️ Sun's up."));
        assertEquals("It's raining cats and dogs! Patched you up.",
                ChatText.plain("It's raining cats and dogs! 🐱🐶 Patched you up."));
    }

    @Test void keepsAccentsAndPunctuation() {
        assertEquals("Zoë's café", ChatText.plain("Zoë's café"));
    }

    @Test void removesColourCodeSign() {
        assertEquals("4red", ChatText.plain("§4red"));
    }

    @Test void emojiOnlyBecomesEmpty() {
        assertEquals("", ChatText.plain("🐶 ☀️"));
    }
}
