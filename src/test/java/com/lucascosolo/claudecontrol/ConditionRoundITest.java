package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Round I: depth 3, node budget, radius 32, place operands, scanRadius, reachFree.
class ConditionRoundITest {
    static final class V implements Condition.View {
        final Map<String, PlaceExpr.Point> pos = new HashMap<>();
        PlaceExpr.Point place;
        @Override public PlaceExpr.Point pos(EntitySelector.Ref ref) { return pos.get(ref.kind() + ":" + ref.value()); }
        @Override public PlaceExpr.Point place(PlaceExpr p) { return place; }
        @Override public double health(EntitySelector.Ref ref) { return 20; }
        @Override public String var(String name) { return null; }
        @Override public int count(EntitySelector filter, int radius) { return 0; }
        @Override public long time() { return 0; }
        @Override public double random() { return 0; }
    }

    private static Condition c(String json) { return Condition.parse(j(json)); }
    private static String chances(int n) { return String.join(",", Collections.nCopies(n, "{'chance':0.5}")); }

    @Test void depthThreeParses() {
        assertDoesNotThrow(() -> c("{'all':[{'all':[{'all':[{'chance':0.5}]}]}]}"));
    }

    @Test void depthFourRefused() {
        rejects(() -> c("{'all':[{'all':[{'all':[{'all':[{'chance':0.5}]}]}]}]}"));
    }

    @Test void nodesCountsCombinators() {
        assertEquals(1, c("{'chance':0.5}").nodes());
        assertEquals(3, c("{'all':[{'chance':0.5},{'chance':0.1}]}").nodes());
    }

    @Test void sixteenNodesAllowedSeventeenRefused() {
        assertEquals(16, c("{'all':[{'any':[" + chances(7) + "]},{'any':[" + chances(6) + "]}]}").nodes());
        rejects(() -> c("{'all':[{'any':[" + chances(7) + "]},{'any':[" + chances(7) + "]}]}"), "16");
    }

    @Test void countRadiusUpTo32() {
        assertDoesNotThrow(() -> c("{'count':{'types':['zombie']},'radius':32,'gt':0}"));
        rejects(() -> c("{'count':{'types':['zombie']},'radius':33,'gt':0}"));
    }

    @Test void nearestRefRadiusUpTo32() {
        assertDoesNotThrow(() -> c("{'distance':['self',{'nearest':{'types':['zombie']},'radius':32}],'lt':5}"));
        rejects(() -> c("{'distance':['self',{'nearest':{'types':['zombie']},'radius':33}],'lt':5}"));
    }

    @Test void scanRadiusIsLargestCountOrNearest() {
        assertEquals(0, c("{'distance':['self','Steve'],'gt':3}").scanRadius());
        assertEquals(8, c("{'count':{'types':['zombie']},'radius':8,'gt':0}").scanRadius());
        assertEquals(24, c("{'all':[{'count':{'types':['zombie']},'radius':8,'gt':0},"
                + "{'distance':['self',{'nearest':{'types':['cow']},'radius':24}],'lt':5}]}").scanRadius());
    }

    @Test void distanceToFixedPlaceUsesViewPlace() {
        V v = new V();
        v.pos.put("SELF:null", new PlaceExpr.Point(0, 0, 0));
        v.place = new PlaceExpr.Point(3, 0, 4);
        assertTrue(c("{'distance':['self',{'world':'world','x':3,'y':0,'z':4}],'gt':4}").test(v));
        assertFalse(c("{'distance':['self',{'world':'world','x':3,'y':0,'z':4}],'gt':5}").test(v));
    }

    @Test void distanceFirstOperandMayBePlace() {
        V v = new V();
        v.pos.put("SELF:null", new PlaceExpr.Point(0, 0, 0));
        v.place = new PlaceExpr.Point(0, 0, 10);
        assertTrue(c("{'distance':[{'world':'world','x':0,'y':0,'z':10},'self'],'eq':10}").test(v));
    }

    @Test void unresolvedPlaceIsFalse() {
        V v = new V();
        v.pos.put("SELF:null", new PlaceExpr.Point(0, 0, 0));
        v.place = null;
        assertFalse(c("{'distance':['self',{'world':'world','x':3,'y':0,'z':4}],'gt':0}").test(v));
    }

    @Test void defaultViewPlaceIsNull() {
        Condition.View v = new Condition.View() {
            public PlaceExpr.Point pos(EntitySelector.Ref ref) { return null; }
            public double health(EntitySelector.Ref ref) { return 0; }
            public String var(String name) { return null; }
            public int count(EntitySelector filter, int radius) { return 0; }
            public long time() { return 0; }
            public double random() { return 0; }
        };
        assertNull(v.place(null));
    }

    @Test void paramAsPlaceNearIsReported() {
        assertEquals(Set.of("owner"), c("{'distance':['self',{'near':'$owner'}],'lt':3}").params());
    }

    @Test void reachFreeSelfAndPlayerOnly() {
        assertTrue(c("{'distance':['self','Steve'],'gt':24}").reachFree());
        assertTrue(c("{'distance':['self',{'near':'Steve'}],'gt':24}").reachFree());
        assertTrue(c("{'health':'self','lt':5}").reachFree());
        assertTrue(c("{'all':[{'chance':0.5},{'time':[0,100]},{'var':'n','eq':1}]}").reachFree());
    }

    @Test void reachFreeFalseForFixedPlaceCountAndNearest() {
        assertFalse(c("{'distance':['self',{'world':'world','x':1,'y':64,'z':1}],'lt':3}").reachFree());
        assertFalse(c("{'count':{'types':['zombie']},'radius':8,'gt':0}").reachFree());
        assertFalse(c("{'distance':['self',{'nearest':{'types':['cow']},'radius':8}],'lt':3}").reachFree());
    }
}
