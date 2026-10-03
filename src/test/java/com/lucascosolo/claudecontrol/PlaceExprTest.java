package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Assumed (v0.3 "places are expressions"), all package-private in com.lucascosolo.claudecontrol:
//   static PlaceExpr PlaceExpr.parse(JsonNode)   throws IllegalArgumentException (message names the problem / limit)
//   String near()  player name or null;  String world()  world name or null (null for near places)
//   record PlaceExpr.Point(double x, double y, double z)
//   Point resolve(Point base, Random rnd)  base = where the near-player is now (ignored for absolute places)
// JSON: {near:"Sam"} | {world,x,y,z}, plus optional offset:{x,y,z} and scatter:{radius, min_radius?, height:[min,max]}.
// Scatter picks a point whose horizontal distance from (base+offset) is in [min_radius, radius] and whose y is
// base.y+offset.y+[hmin,hmax]. Unspecified in the PRD: general scatter limits, so only absurd values are tested (radius 10000).
class PlaceExprTest {
    private static final PlaceExpr.Point BASE = new PlaceExpr.Point(100, 64, -50);

    @Test void nearResolvesToBase() {
        PlaceExpr p = PlaceExpr.parse(j("{'near':'Sam'}"));
        assertEquals("Sam", p.near());
        assertNull(p.world());
        assertEquals(BASE, p.resolve(BASE, new Random(1)));
    }

    @Test void absoluteIgnoresBase() {
        PlaceExpr p = PlaceExpr.parse(j("{'world':'world','x':5,'y':70,'z':-9}"));
        assertEquals("world", p.world());
        assertNull(p.near());
        assertEquals(new PlaceExpr.Point(5, 70, -9), p.resolve(BASE, new Random(1)));
    }

    @Test void offsetIsAddedToBase() {
        PlaceExpr p = PlaceExpr.parse(j("{'near':'Sam','offset':{'x':2,'y':10,'z':-3}}"));
        assertEquals(new PlaceExpr.Point(102, 74, -53), p.resolve(BASE, new Random(1)));
    }

    @Test void scatterStaysInAnnulusAndHeightRange() {
        PlaceExpr p = PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':10,'min_radius':4,'height':[15,25]}}"));
        Random r = new Random(7);
        for (int i = 0; i < 500; i++) {
            PlaceExpr.Point q = p.resolve(BASE, r);
            double d = Math.hypot(q.x() - BASE.x(), q.z() - BASE.z());
            assertTrue(d >= 4 - 1e-9 && d <= 10 + 1e-9, "distance " + d);
            assertTrue(q.y() >= BASE.y() + 15 - 1e-9 && q.y() <= BASE.y() + 25 + 1e-9, "y " + q.y());
        }
    }

    @Test void scatterWithoutMinRadiusFillsTheDisc() {
        PlaceExpr p = PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':8,'height':[10,10]}}"));
        Random r = new Random(3);
        double min = Double.MAX_VALUE, max = 0;
        for (int i = 0; i < 2000; i++) {
            PlaceExpr.Point q = p.resolve(BASE, r);
            double d = Math.hypot(q.x() - BASE.x(), q.z() - BASE.z());
            min = Math.min(min, d); max = Math.max(max, d);
            assertEquals(BASE.y() + 10, q.y(), 1e-9);
        }
        assertTrue(min < 1.5 && max > 6.5 && max <= 8 + 1e-9, min + ".." + max);
    }

    @Test void scatterDiffersAcrossCallsAndIsDeterministicForSeed() {
        PlaceExpr p = PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':12,'height':[5,20]}}"));
        Random r = new Random(11);
        Set<PlaceExpr.Point> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) seen.add(p.resolve(BASE, r));
        assertTrue(seen.size() > 40, "points should differ: " + seen.size());
        assertEquals(p.resolve(BASE, new Random(99)), p.resolve(BASE, new Random(99)));
    }

    @Test void invalidPlacesRejected() {
        rejects(() -> PlaceExpr.parse(j("{}")));
        rejects(() -> PlaceExpr.parse(j("{'near':''}")));
        rejects(() -> PlaceExpr.parse(j("{'near':'Sam','world':'w','x':1,'y':1,'z':1}")));
        rejects(() -> PlaceExpr.parse(j("{'world':'w','x':1}")));
        rejects(() -> PlaceExpr.parse(j("{'near':'@a'}")));
        rejects(() -> PlaceExpr.parse(j("{'near':'Sam','surprise':1}")), "surprise");
        rejects(() -> PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':5,'height':[1,2],'wat':1}}")), "wat");
    }

    @Test void scatterLimitsRejected() {
        rejects(() -> PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':0,'height':[1,2]}}")));
        rejects(() -> PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':-3,'height':[1,2]}}")));
        rejects(() -> PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':10000,'height':[1,2]}}")));
        rejects(() -> PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':5,'min_radius':6,'height':[1,2]}}")));
        rejects(() -> PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':5,'height':[9,2]}}")));
        rejects(() -> PlaceExpr.parse(j("{'near':'Sam','scatter':{'radius':5,'height':[1000,2000]}}")));
    }

    @Test void bedrockNamesWithLeadingDotAreAccepted() {
        assertEquals(".lucascosolo", PlaceExpr.parse(j("{'near':'.lucascosolo'}")).near());
        rejects(() -> PlaceExpr.parse(j("{'near':'@a'}")));
        rejects(() -> PlaceExpr.parse(j("{'near':'..x'}")));
    }
}
