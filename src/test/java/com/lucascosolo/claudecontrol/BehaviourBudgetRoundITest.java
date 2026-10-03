package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

// Round I: scan and particle budgets, and the per-behaviour Pacer.
class BehaviourBudgetRoundITest {
    private final AtomicLong tick = new AtomicLong(1);
    private final BehaviourBudget b = new BehaviourBudget(tick::get);

    @Test void constants() {
        assertEquals(4, BehaviourBudget.SCANS_PER_TICK);
        assertEquals(400, BehaviourBudget.PARTICLES_PER_TICK);
    }

    @Test void fourScansPerEntityPerTick() {
        for (int i = 0; i < 4; i++) assertTrue(b.scan("a"), "scan " + i);
        assertFalse(b.scan("a"));
        assertTrue(b.scan("b"));
        tick.incrementAndGet();
        assertTrue(b.scan("a"));
    }

    @Test void particlesGlobalFourHundred() {
        assertTrue(b.particles(300));
        assertFalse(b.particles(101));
        assertTrue(b.particles(100));
        assertFalse(b.particles(1));
        tick.incrementAndGet();
        assertTrue(b.particles(400));
    }

    @Test void pacerGaps() {
        var p = new BehaviourBudget.Pacer();
        assertTrue(p.allow("say", 100));
        assertFalse(p.allow("say", 139));
        assertTrue(p.allow("say", 140));
        assertTrue(p.allow("sound", 100));
        assertFalse(p.allow("sound", 109));
        assertTrue(p.allow("sound", 110));
        assertTrue(p.allow("particles", 100));
        assertFalse(p.allow("particles", 104));
        assertTrue(p.allow("particles", 105));
    }

    @Test void pacerOtherKindsAlwaysAndPerInstance() {
        var p = new BehaviourBudget.Pacer();
        assertTrue(p.allow("jump", 1));
        assertTrue(p.allow("jump", 1));
        assertTrue(p.allow("say", 5));
        assertTrue(new BehaviourBudget.Pacer().allow("say", 5));
    }
}
