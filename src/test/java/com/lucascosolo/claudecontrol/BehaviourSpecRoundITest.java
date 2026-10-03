package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Round I: budgets, typed params, scan radius, authority gates, reach-free rules, expressiveness.
class BehaviourSpecRoundITest {
    private static BehaviourSpec p(String json) { return BehaviourSpec.parse(j(json)); }
    private static String rule(String every) { return "{'when':{'every':" + every + "},'do':[{'stop':true}]}"; }
    private static String withRules(String rules) { return "{'name':'n','rules':[" + rules + "]}"; }
    private static String chances(int n) { return String.join(",", Collections.nCopies(n, "{'chance':0.5}")); }
    /** A 13-node condition: all + any(6) + any(4), plus the combinators. */
    private static String thirteen() { return "{'all':[{'any':[" + chances(6) + "]},{'any':[" + chances(4) + "]}]}"; }
    private static String ruleIf(String cond) { return "{'when':{'every':20},'if':" + cond + ",'do':[{'stop':true}]}"; }
    private static BehaviourSpec.Rule r(String json) { return p(withRules(json)).rules().get(0); }
    private static final PlaceExpr.Point BASE = new PlaceExpr.Point(100, 70, 100);
    private static PlaceExpr.Point at(PlaceExpr e) { return e.resolve(BASE, new Random(1)); }

    // --- budgets
    @Test void everyMinimumIsFive() {
        assertNotNull(p(withRules(rule("5"))));
        rejects(() -> p(withRules(rule("4"))));
    }

    @Test void totalConditionNodesAtMost48() {
        String ok = String.join(",", Collections.nCopies(3, ruleIf(thirteen())));
        assertEquals(3, p(withRules(ok)).rules().size());
        String over = String.join(",", Collections.nCopies(4, ruleIf(thirteen())));
        rejects(() -> p(withRules(over)), "48");
    }

    @Test void rawDefinitionOver8KbRefused() {
        String say = "{'say':'" + "x".repeat(100) + "'}";
        String rl = "{'when':{'every':20},'do':[" + String.join(",", Collections.nCopies(8, say)) + "]}";
        rejects(() -> p(withRules(String.join(",", Collections.nCopies(12, rl)))), "8 KB");
    }

    // --- typed params
    @Test void undeclaredParamsStayPlayerTyped() {
        BehaviourSpec b = p("{'name':'n','rules':[{'when':{'every':10},'do':[{'walk_to':{'near':'$owner'}}]}]}");
        assertEquals(Set.of("owner"), b.params());
        assertEquals("player", b.paramTypes().getOrDefault("owner", "player"));
    }

    @Test void declaredParamTypesRecorded() {
        BehaviourSpec b = p("{'name':'n','params':{'post':'place','n':'number','who':'player','e':'entity'},'rules':[" + rule("20") + "]}");
        assertEquals("place", b.paramTypes().get("post"));
        assertEquals("number", b.paramTypes().get("n"));
        assertEquals("player", b.paramTypes().get("who"));
        assertEquals("entity", b.paramTypes().get("e"));
        assertEquals(Set.of("post", "n", "who", "e"), b.params());
    }

    @Test void unknownParamTypeRefused() {
        rejects(() -> p("{'name':'n','params':{'post':'colour'},'rules':[" + rule("20") + "]}"));
    }

    @Test void placeParamParsesWhereAPlaceIsExpected() {
        String d = "{'name':'n','params':{'post':'place'},'rules':[";
        assertEquals(Set.of("post"), p(d + "{'when':{'every':20},'do':[{'walk_to':'$post'}]}]}").params());
        assertNotNull(p(d + "{'when':{'every':20},'do':[{'teleport':'$post'}]}]}"));
        assertNotNull(p(d + ruleIf("{'distance':['self','$post'],'gt':4}") + "]}"));
    }

    @Test void numberParamParsesWhereANumberIsExpected() {
        // speed range is 0.1..3, every is 5+, a number placeholder must be one that parses (1, 5 or 20)
        assertNotNull(p("{'name':'n','params':{'secs':'number'},'rules':[{'when':{'every':20},'do':[{'walk_to':{'near':'self'},'speed':'$secs'}]}]}"));
    }

    // --- bind by type
    private static final String PLACE_DEF = "{'name':'n','params':{'post':'place'},'rules':[{'when':{'every':20},'do':[{'walk_to':'$post'}]}]}";
    private static Map<String, JsonNode> one(String k, String json) { return Map.of(k, j(json)); }

    @Test void bindPlaceSubstitutesObject() {
        BehaviourSpec b = BehaviourSpec.bindTyped(j(PLACE_DEF), one("post", "{'world':'world','x':5,'y':64,'z':-3}"));
        assertEquals(Set.of(), b.params());
        assertEquals(new PlaceExpr.Point(5, 64, -3), at(b.rules().get(0).actions().get(0).place()));
    }

    @Test void bindPlaceWrongShapesRefusedNamingParam() {
        for (String bad : new String[] {"'home'", "5", "{'world':'world','x':1,'y':2}", "{'world':'world','x':1,'y':2,'z':3,'extra':1}",
                "{'world':5,'x':1,'y':2,'z':3}", "{'world':'world','x':'a','y':2,'z':3}"})
            rejects(() -> BehaviourSpec.bindTyped(j(PLACE_DEF), one("post", bad)), "post");
    }

    @Test void bindPlayerTypeNeedsName() {
        String def = "{'name':'n','params':{'who':'player'},'rules':[{'when':{'every':20},'do':[{'walk_to':{'near':'$who'}}]}]}";
        BehaviourSpec b = BehaviourSpec.bindTyped(j(def), one("who", "'Steve'"));
        assertEquals("Steve", b.rules().get(0).actions().get(0).place().nearRef().value());
        for (String bad : new String[] {"'a b'", "'this_name_is_way_too_long'", "5", "{'x':1}"})
            rejects(() -> BehaviourSpec.bindTyped(j(def), one("who", bad)), "who");
    }

    @Test void bindEntityTypeNeedsUuid() {
        String def = "{'name':'n','params':{'e':'entity'},'rules':[{'when':{'every':20},'do':[{'walk_to':{'near':'$e'}}]}]}";
        assertNotNull(BehaviourSpec.bindTyped(j(def), one("e", "'123e4567-e89b-12d3-a456-426614174000'")));
        rejects(() -> BehaviourSpec.bindTyped(j(def), one("e", "'Steve'")), "e");
    }

    @Test void bindNumberTypeNeedsFiniteNumber() {
        String def = "{'name':'n','params':{'s':'number'},'rules':[{'when':{'every':20},'do':[{'walk_to':{'near':'self'},'speed':'$s'}]}]}";
        BehaviourSpec b = BehaviourSpec.bindTyped(j(def), one("s", "1.5"));
        assertEquals(1.5, b.rules().get(0).actions().get(0).amount(), 1e-9);
        rejects(() -> BehaviourSpec.bindTyped(j(def), one("s", "'fast'")), "s");
    }

    @Test void bindStringMapStillWorks() {
        String def = "{'name':'n','rules':[{'when':{'every':20},'do':[{'walk_to':{'near':'$owner'}}]}]}";
        BehaviourSpec b = BehaviourSpec.bind(j(def), Map.of("owner", "Lucas"));
        assertEquals("Lucas", b.rules().get(0).actions().get(0).place().nearRef().value());
    }

    // --- scan radius
    @Test void scanRadiusFromConditionsAndActionSubjects() {
        assertEquals(0, p(withRules(rule("20"))).scanRadius());
        assertEquals(12, p(withRules(ruleIf("{'count':{'types':['zombie']},'radius':12,'gt':0}"))).scanRadius());
        assertEquals(20, p(withRules("{'when':{'every':20},'do':[{'target':{'nearest':{'types':['zombie']},'radius':20}}]}")).scanRadius());
    }

    // --- authority gates
    private static Authority auth(boolean privileged) { return new Authority(List.of("Steve"), privileged, false, false); }

    @Test void radiusRefusal() {
        assertNull(BehaviourSpec.radiusRefusal(16, auth(false)));
        assertNull(BehaviourSpec.radiusRefusal(32, auth(true)));
        assertNull(BehaviourSpec.radiusRefusal(10, auth(true)));
        assertTrue(BehaviourSpec.radiusRefusal(17, auth(false)).contains("16"));
        assertTrue(BehaviourSpec.radiusRefusal(32, auth(false)).contains("16"));
    }

    @Test void attachRefusalOnlyWhenPrivilegedAttachesUnprivilegedDefinition() {
        assertTrue(BehaviourSpec.attachRefusal(false, auth(true), false).contains("confirm_rules"));
        assertNull(BehaviourSpec.attachRefusal(false, auth(true), true));
        assertNull(BehaviourSpec.attachRefusal(true, auth(true), false));
        assertNull(BehaviourSpec.attachRefusal(false, auth(false), false));
        assertNull(BehaviourSpec.attachRefusal(true, auth(false), false));
    }

    // --- reach-free rules
    @Test void followRuleIsReachFree() {
        assertTrue(r("{'when':{'every':20},'if':{'distance':['self','Steve'],'gt':24},'do':[{'teleport':{'near':'Steve'}}]}").reachFree());
    }

    @Test void simpleSelfActionsReachFree() {
        assertTrue(r("{'when':{'every':20},'do':[{'stop':true},{'jump':true}]}").reachFree());
    }

    @Test void nearestPlayerTargetNotReachFree() {
        assertFalse(r("{'when':{'every':20},'do':[{'target':'nearest_player'}]}").reachFree());
    }

    @Test void fixedPlaceNotReachFree() {
        assertFalse(r("{'when':{'every':20},'do':[{'walk_to':{'world':'world','x':0,'y':64,'z':0}}]}").reachFree());
    }

    @Test void nonMovementActionKindNotReachFree() {
        assertFalse(r("{'when':{'every':20},'do':[{'say':'hi'}]}").reachFree());
    }

    @Test void unreachFreeConditionMakesRuleNotReachFree() {
        assertFalse(r(ruleIf("{'count':{'types':['zombie']},'radius':8,'gt':0}")).reachFree());
    }

    // --- expressiveness
    @Test void patrolBuildsAndBinds() {
        String def = "{'name':'patrol','params':{'a':'place','b':'place'},'vars':{'leg':0},'rules':["
                + "{'when':{'every':20},'if':{'all':[{'var':'leg','eq':0},{'distance':['self','$a'],'lt':2}]},'do':[{'var':'leg','set':1}]},"
                + "{'when':{'every':20},'if':{'all':[{'var':'leg','eq':1},{'distance':['self','$b'],'lt':2}]},'do':[{'var':'leg','set':0}]},"
                + "{'when':{'every':20},'if':{'var':'leg','eq':0},'do':[{'walk_to':'$a'}]},"
                + "{'when':{'every':20},'if':{'var':'leg','eq':1},'do':[{'walk_to':'$b'}]}]}";
        assertEquals(Set.of("a", "b"), p(def).params());
        BehaviourSpec b = BehaviourSpec.bindTyped(j(def), Map.of("a", j("{'world':'world','x':1,'y':64,'z':2}"), "b", j("{'world':'world','x':30,'y':65,'z':40}")));
        assertEquals(4, b.rules().size());
        assertEquals(new PlaceExpr.Point(1, 64, 2), at(b.rules().get(2).actions().get(0).place()));
        assertEquals(new PlaceExpr.Point(30, 65, 40), at(b.rules().get(3).actions().get(0).place()));
    }

    @Test void guardBuildsAndBinds() {
        String def = "{'name':'guard','params':{'post':'place'},'rules':["
                + "{'when':{'every':10},'if':{'count':{'types':['zombie']},'radius':16,'gt':0},'do':[{'target':{'nearest':{'types':['zombie']},'radius':16}}]},"
                + "{'when':{'every':20},'if':{'distance':['self','$post'],'gt':4},'do':[{'walk_to':'$post'}]}]}";
        assertEquals(Set.of("post"), p(def).params());
        BehaviourSpec b = BehaviourSpec.bindTyped(j(def), Map.of("post", j("{'world':'world','x':7,'y':63,'z':9}")));
        assertEquals(2, b.rules().size());
        assertEquals(16, b.scanRadius());
        assertEquals(new PlaceExpr.Point(7, 63, 9), at(b.rules().get(1).actions().get(0).place()));
    }
}
