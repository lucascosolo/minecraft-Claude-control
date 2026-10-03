package com.lucascosolo.claudecontrol;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/** Serves turns with the primary; when it is unavailable, the same turn and the next five minutes go to the secondary. */
public final class FallbackBackend implements AgentBackend {
    static final Duration SKIP = Duration.ofMinutes(5);
    private final AgentBackend primary, secondary;
    private final Clock clock;
    private final Logger log;
    private Instant skipUntil = Instant.MIN;
    private volatile AgentBackend lastServed;

    public FallbackBackend(AgentBackend primary, AgentBackend secondary, Clock clock, Logger log) {
        this.primary = primary;
        this.secondary = secondary;
        this.clock = clock;
        this.log = log;
    }

    @Override
    public void runTurn(String userTurn, String actors) throws Exception {
        if (secondary != null && clock.instant().isBefore(skipUntil)) {
            lastServed = secondary;
            secondary.runTurn(userTurn, actors);
            return;
        }
        try {
            lastServed = primary;
            primary.runTurn(userTurn, actors);
        } catch (BackendUnavailable e) {
            if (secondary == null) throw e;
            skipUntil = clock.instant().plus(SKIP);
            log.warning(primary.describe() + " is unavailable (" + e.getMessage() + "); this turn and the next "
                    + SKIP.toMinutes() + " minutes go to " + secondary.describe() + ".");
            lastServed = secondary;
            secondary.runTurn(userTurn, actors);
        }
    }

    /** The backend the next turn will be sent to first. */
    AgentBackend next() {
        return secondary != null && clock.instant().isBefore(skipUntil) ? secondary : primary;
    }

    @Override
    public void reset() {
        primary.reset();
        if (secondary != null) secondary.reset();
    }

    @Override
    public void close() {
        primary.close();
        if (secondary != null) secondary.close();
    }

    @Override
    public void stop() {
        primary.stop();
        if (secondary != null) secondary.stop();
    }

    @Override
    public String describe() {
        AgentBackend b = lastServed;
        return b == null ? primary.describe() : b.describe();
    }
}
