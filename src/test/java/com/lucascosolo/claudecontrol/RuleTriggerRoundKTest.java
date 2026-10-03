package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Round K: who on damaged/died/hits/target_changed, Event.about, whoRefusal, block_changes.
class RuleTriggerRoundKTest {
    private static final List<String> KINDS = List.of("damaged", "died", "hits", "target_changed");
    private static RuleTrigger p(String json) { return RuleTrigger.parse(j(json)); }
    private static RuleTrigger.Event ev(String kind, RuleTrigger.Subject about) {
        return new RuleTrigger.Event(kind, "Zed", null, null, null, null, about);
    }
    private static RuleTrigger.Subject sub(String player, String uuid, String... tags) {
        return new RuleTrigger.Subject(player, uuid, Set.of(tags));
    }
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    @Test void whoParses() {
        for (String k : KINDS) {
            assertNull(p("{'" + k + "':true}").who());
            assertNull(p("{'" + k + "':{'who':'self'}}").who());
            assertEquals(EntitySelector.Ref.Kind.PLAYER, p("{'" + k + "':{'who':'Lucas'}}").who().kind());
            assertEquals(EntitySelector.Ref.Kind.PARAM, p("{'" + k + "':{'who':'$owner'}}").who().kind());
            assertEquals(EntitySelector.Ref.Kind.ENTITY, p("{'" + k + "':{'who':{'entity':'" + ID + "'}}}").who().kind());
            assertEquals(EntitySelector.Ref.Kind.TAG, p("{'" + k + "':{'who':{'entity':'vip'}}}").who().kind());
        }
    }

    @Test void whoRefusals() {
        for (String k : KINDS) {
            rejects(() -> p("{'" + k + "':{'who':'attacker'}}"));
            rejects(() -> p("{'" + k + "':{'who':'nearest_player'}}"));
            rejects(() -> p("{'" + k + "':{'who':{'nearest':{'type':'zombie'}}}}"));
        }
    }

    @Test void matchesWithoutWhoOnlySelf() {
        for (String k : KINDS) {
            RuleTrigger t = p("{'" + k + "':true}");
            assertTrue(t.matches(ev(k, null)));
            assertFalse(t.matches(ev(k, sub("Lucas", null))));
            assertFalse(t.matches(ev("every", null)));
        }
    }

    @Test void matchesWithWho() {
        RuleTrigger pl = p("{'damaged':{'who':'Lucas'}}");
        assertTrue(pl.matches(ev("damaged", sub("lucas", null))));
        assertFalse(pl.matches(ev("damaged", sub("Zed", null))));
        assertFalse(pl.matches(ev("damaged", null)));
        assertFalse(pl.matches(ev("died", sub("Lucas", null))));
        RuleTrigger en = p("{'died':{'who':{'entity':'" + ID + "'}}}");
        assertTrue(en.matches(ev("died", sub(null, ID))));
        assertFalse(en.matches(ev("died", sub(null, "other"))));
        RuleTrigger tg = p("{'hits':{'who':{'entity':'vip'}}}");
        assertTrue(tg.matches(ev("hits", sub(null, ID, "vip", "x"))));
        assertFalse(tg.matches(ev("hits", sub(null, ID, "x"))));
    }

    @Test void whoRefusal() {
        Authority sam = new Authority(List.of("Sam"), false, false, true);
        Authority priv = new Authority(List.of("Sam"), true, false, true);
        var P = EntitySelector.Ref.Kind.PLAYER;
        assertNull(RuleTrigger.whoRefusal(null, sam));
        assertNull(RuleTrigger.whoRefusal(p("{'damaged':{'who':'sam'}}").who(), sam));
        assertNotNull(RuleTrigger.whoRefusal(p("{'damaged':{'who':'Alex'}}").who(), sam));
        assertNull(RuleTrigger.whoRefusal(p("{'damaged':{'who':'Alex'}}").who(), priv));
        assertNull(RuleTrigger.whoRefusal(p("{'damaged':{'who':{'entity':'vip'}}}").who(), sam));
        assertNull(RuleTrigger.whoRefusal(p("{'damaged':{'who':{'entity':'" + ID + "'}}}").who(), sam));
    }

    @Test void blockChanges() {
        RuleTrigger t = p("{'block_changes':{'at':{'world':'world','x':1,'y':64,'z':2}}}");
        assertEquals("block_changes", t.kind());
        assertTrue(t.polled());
        assertEquals(0, t.scanRadius());
        assertEquals(1.0, t.at().absolute().x());
        assertEquals(10, RuleTrigger.BLOCK_POLL);
        assertEquals("Lucas", p("{'block_changes':{'at':{'near':'Lucas'}}}").at().near());
        rejects(() -> p("{'block_changes':{'at':{'away_from':'Sam','distance':8}}}"));
        rejects(() -> p("{'block_changes':{'at':{'near':'Lucas'},'radius':3}}"));
        rejects(() -> p("{'block_changes':true}"));
    }
}
