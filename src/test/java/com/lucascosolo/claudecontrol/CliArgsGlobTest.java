package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

// H6: a work dir holding a glob character would turn the CLI's file rules into patterns.
class CliArgsGlobTest {
    @Test void refusesEachGlobCharacter() {
        for (String c : new String[]{"*", "?", "[", "]", "{", "}"}) {
            String r = CliArgs.workDirRefusal(Path.of("/srv/mc/claude" + c + "work"));
            assertNotNull(r, c);
            assertTrue(r.contains("glob"), c);
        }
    }

    @Test void plainPathPasses() {
        assertNull(CliArgs.workDirRefusal(Path.of("/srv/mc/claude-work")));
    }
}
