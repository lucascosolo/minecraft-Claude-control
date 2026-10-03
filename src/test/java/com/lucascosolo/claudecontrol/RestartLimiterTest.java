package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Changed from the 10-minute rule (PRD v0.2 item 30). Assumed signatures:
//   RestartLimiter(java.time.Clock clock, int minMinutesBetween, int maxPerDay)
//   boolean tryAcquire(); long secondsUntilAllowed()   (unchanged; seconds rounded up, 0 = allowed now)
// The daily cap is a rolling 24 h window over successful acquires.
class RestartLimiterTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
    private final RestartLimiter l = new RestartLimiter(clock, 30, 4);

    @Test void firstRestartAllowed() {
        assertEquals(0, l.secondsUntilAllowed());
        assertTrue(l.tryAcquire());
    }

    @Test void secondRestartWithinMinGapRefusedAndReportsWhen() {
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
        assertEquals(1800, l.secondsUntilAllowed());
        clock.advance(Duration.ofSeconds(100));
        assertFalse(l.tryAcquire());
        assertEquals(1700, l.secondsUntilAllowed());
    }

    @Test void tenMinutesIsNoLongerEnough() {
        assertTrue(l.tryAcquire());
        clock.advance(Duration.ofMinutes(11));
        assertFalse(l.tryAcquire());
    }

    @Test void refusedAttemptDoesNotExtendTheWindow() {
        assertTrue(l.tryAcquire());
        clock.advance(Duration.ofSeconds(1799));
        assertFalse(l.tryAcquire());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(0, l.secondsUntilAllowed());
        assertTrue(l.tryAcquire());
    }

    @Test void minGapIsConfigurable() {
        var fast = new RestartLimiter(clock, 5, 100);
        assertTrue(fast.tryAcquire());
        clock.advance(Duration.ofMinutes(6));
        assertTrue(fast.tryAcquire());
    }

    @Test void dailyCapStopsTheFifthRestartWithinTwentyFourHours() {
        for (int i = 0; i < 4; i++) {
            assertTrue(l.tryAcquire(), "restart " + i);
            if (i < 3) clock.advance(Duration.ofMinutes(30));
        }
        // now t = 90 min; next allowed when the first (t=0) leaves the 24 h window
        assertEquals(24 * 3600 - 90 * 60, l.secondsUntilAllowed());
        clock.advance(Duration.ofMinutes(30)); // t = 120 min, min gap long passed, cap still hit
        assertFalse(l.tryAcquire());
        assertEquals(24 * 3600 - 120 * 60, l.secondsUntilAllowed());
    }

    @Test void dailyCapReleasesWhenOldestLeavesWindow() {
        for (int i = 0; i < 4; i++) {
            assertTrue(l.tryAcquire());
            if (i < 3) clock.advance(Duration.ofMinutes(30));
        }
        clock.advance(Duration.ofHours(24).minusMinutes(90)); // t = exactly 24 h
        assertEquals(0, l.secondsUntilAllowed());
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire()); // min gap again
    }
}
