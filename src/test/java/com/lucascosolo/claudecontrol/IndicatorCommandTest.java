package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IndicatorCommandTest {
    private static final String BUILDING = "Claude is building a plugin…";
    private static final String WORKING = "Claude is working…";

    @Test void nullToolIsThinking() {
        assertEquals("Claude is thinking…", IndicatorText.of(null, null));
        assertEquals("Claude is thinking…", IndicatorText.of(null, "./gradlew build"));
    }

    @Test void gradleInsideWorkshopIsBuilding() {
        assertEquals(BUILDING, IndicatorText.of("Bash", "cd workshop/Rainbow && ./gradlew build -q"));
        assertEquals(BUILDING, IndicatorText.of("Bash", "./gradlew -p workshop/Rainbow build"));
        assertEquals(BUILDING, IndicatorText.of("Bash", "cd workshop/Rainbow && gradle shadowJar"));
    }

    @Test void bashOtherwiseIsWorking() {
        assertEquals(WORKING, IndicatorText.of("Bash", null));
        assertEquals(WORKING, IndicatorText.of("Bash", "ls"));
        assertEquals(WORKING, IndicatorText.of("Bash", "./gradlew build"));
        assertEquals(WORKING, IndicatorText.of("Bash", "gradle test"));
    }

    @Test void gradleOnlyAsFilenameIsNotAGradleRun() {
        assertEquals(WORKING, IndicatorText.of("Bash", "cat workshop/Rainbow/build.gradle.kts"));
    }

    @Test void workshopWithoutGradleIsWorking() {
        assertEquals(WORKING, IndicatorText.of("Bash", "ls workshop/Rainbow"));
    }

    @Test void otherToolsUnchanged() {
        assertEquals("Claude is working: build", IndicatorText.of("mcp__game__build", null));
        assertEquals("Claude is working: build", IndicatorText.of("mcp__game__build", "./gradlew workshop/"));
        assertEquals("Claude is working: " + "x".repeat(32), IndicatorText.of("x".repeat(60), null));
        assertEquals("Claude is working: ab1", IndicatorText.of("a b-§1", null));
    }

    @Test void singleArgEqualsNullCommand() {
        for (String tool : new String[] {null, "Bash", "mcp__game__build", "survey_area"}) {
            assertEquals(IndicatorText.of(tool, null), IndicatorText.of(tool));
        }
    }
}
