package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static com.lucascosolo.claudecontrol.TestJson.rejects;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

// Round I: new act kinds, entity. variables, and the reach/teleport/target/remove/riders refusals.
class ActSpecRoundITest {
    private static ActSpec.Action a(String json) { return ActSpec.action(j(json)); }
    private static Authority auth(boolean privileged, String... players) { return new Authority(List.of(players), privileged, false, false); }
    private static final Authority SAM = auth(false, "Sam");
    private static final Authority OWNER = auth(true, "Sam");
    private static final EntitySelector.Ref.Kind ENT = EntitySelector.Ref.Kind.ENTITY;

    @Test void newKindsListed() {
        assertTrue(ActSpec.KINDS.containsAll(List.of("attack", "swing", "mount", "dismount", "animation")));
    }

    @Test void particlesCountMaxTwenty() {
        assertEquals(10.0, a("{'particles':'heart'}").extra(), 1e-9);
        assertEquals(20.0, a("{'particles':'heart','count':20}").extra(), 1e-9);
        rejects(() -> a("{'particles':'heart','count':21}"));
    }

    @Test void attackTakesSubjectAndIsHostileMaking() {
        ActSpec.Action x = a("{'attack':'nearest_player'}");
        assertEquals("attack", x.kind());
        assertEquals(EntitySelector.Ref.Kind.NEAREST_PLAYER, x.subject().kind());
        assertTrue(x.hostileMaking());
        assertEquals(java.util.Set.of("victim"), a("{'attack':'$victim'}").params());
    }

    @Test void swingForms() {
        assertEquals("main", a("{'swing':true}").text());
        assertEquals("main", a("{'swing':'main'}").text());
        assertEquals("off", a("{'swing':'off'}").text());
        rejects(() -> a("{'swing':'left'}"));
    }

    @Test void mountTakesSubject() {
        ActSpec.Action x = a("{'mount':'Sam'}");
        assertEquals(EntitySelector.Ref.Kind.PLAYER, x.subject().kind());
        assertEquals(java.util.Set.of("horse"), a("{'mount':'$horse'}").params());
    }

    @Test void dismountOnlyTrue() {
        assertEquals("dismount", a("{'dismount':true}").kind());
        rejects(() -> a("{'dismount':false}"));
    }

    @Test void animationAllowList() {
        for (String s : List.of("hurt", "love_hearts", "villager_happy", "villager_angry", "wolf_shake"))
            assertEquals(s, a("{'animation':'" + s + "'}").text());
        rejects(() -> a("{'animation':'explode'}"));
    }

    @Test void varNameEntityPrefix() {
        assertEquals("entity.hp", ActSpec.varName(j("'entity.hp'")));
        rejects(() -> ActSpec.varName(j("'entity.'")));
        rejects(() -> ActSpec.varName(j("'entity.Bad'")));
        rejects(() -> ActSpec.varName(j("'other.hp'")));
    }

    // --- reachRefusal
    @Test void reachNeverNamesAPlayerEntityEvenPrivileged() {
        for (var k : List.of(ENT, EntitySelector.Ref.Kind.TAG, EntitySelector.Ref.Kind.NEAREST)) {
            String m = ActSpec.reachRefusal(k, true, "Sam", 1, OWNER);
            assertNotNull(m);
            assertTrue(m.contains("player"), m);
        }
    }

    @Test void reachPrivilegedAndSelfUnlimited() {
        assertNull(ActSpec.reachRefusal(ENT, false, null, 5000, OWNER));
        assertNull(ActSpec.reachRefusal(null, false, null, 5000, OWNER));
        assertNull(ActSpec.reachRefusal(EntitySelector.Ref.Kind.SELF, false, null, 5000, SAM));
    }

    @Test void reachPlayersOnlyTurnPlayersAnyDistance() {
        assertNull(ActSpec.reachRefusal(EntitySelector.Ref.Kind.PLAYER, true, "sAm", 5000, SAM));
        assertNull(ActSpec.reachRefusal(EntitySelector.Ref.Kind.NEAREST_PLAYER, true, "Sam", 1, SAM));
        assertNotNull(ActSpec.reachRefusal(EntitySelector.Ref.Kind.PLAYER, true, "Bob", 1, SAM));
        assertNotNull(ActSpec.reachRefusal(EntitySelector.Ref.Kind.PARAM, true, "Bob", 1, SAM));
    }

    @Test void reachEntitiesAndPlacesWithinSixtyFour() {
        for (var k : new EntitySelector.Ref.Kind[] {ENT, EntitySelector.Ref.Kind.TAG, EntitySelector.Ref.Kind.NEAREST, null}) {
            assertNull(ActSpec.reachRefusal(k, false, null, 64, SAM));
            String m = ActSpec.reachRefusal(k, false, null, 64.5, SAM);
            assertNotNull(m);
            assertTrue(m.contains("64"), m);
        }
    }

    // --- teleport, target, remove, riders
    @Test void teleportRefusesHostileMobForNonOwner() {
        String m = ActSpec.teleportRefusal(true, SAM);
        assertNotNull(m);
        assertTrue(m.contains("owner"), m);
        assertNull(ActSpec.teleportRefusal(true, OWNER));
        assertNull(ActSpec.teleportRefusal(false, SAM));
    }

    @Test void targetRefusal() {
        assertNull(ActSpec.targetRefusal("Bob", true, true, OWNER));
        assertNull(ActSpec.targetRefusal(null, false, false, SAM));
        assertNull(ActSpec.targetRefusal("sam", false, false, SAM));
        assertNotNull(ActSpec.targetRefusal("Bob", false, false, SAM));
        assertNotNull(ActSpec.targetRefusal(null, true, false, SAM));
        assertNotNull(ActSpec.targetRefusal(null, false, true, SAM));
    }

    @Test void removeRefusal() {
        assertNotNull(ActSpec.removeRefusal(true, false, "sam", SAM));
        assertNotNull(ActSpec.removeRefusal(false, true, "sam", SAM));
        assertNull(ActSpec.removeRefusal(true, true, null, OWNER));
        assertNull(ActSpec.removeRefusal(false, false, null, OWNER));
        assertNull(ActSpec.removeRefusal(false, false, "bob,SAM".toLowerCase(), SAM));
        assertNotNull(ActSpec.removeRefusal(false, false, "bob", SAM));
        String m = ActSpec.removeRefusal(false, false, null, SAM);
        assertNotNull(m);
        assertTrue(m.contains("spawn"), m);
    }

    @Test void ridersRefusal() {
        assertNull(ActSpec.ridersRefusal(List.of("Zed"), OWNER));
        assertNull(ActSpec.ridersRefusal(List.of("sam"), SAM));
        assertNull(ActSpec.ridersRefusal(List.of(), SAM));
        String m = ActSpec.ridersRefusal(List.of("Sam", "Zed", "Yan"), SAM);
        assertNotNull(m);
        assertTrue(m.contains("Zed") && !m.contains("Yan"), m);
    }

    /** Review of round I: an offset on a near place must not send a mob far away (teleport would generate chunks). */
    @Test void offsetCannotCarryANearPlaceFarAway() {
        assertNull(ActSpec.pointRefusal(true, 500, 10, SAM));        // far from the mob, close to the turn player: follow works
        assertNotNull(ActSpec.pointRefusal(true, 500, 49, SAM));     // offset from the player too large
        assertNull(ActSpec.pointRefusal(false, 64, 64, SAM));
        assertNotNull(ActSpec.pointRefusal(false, 65, 65, SAM));     // near self with offset 5000
        assertNull(ActSpec.pointRefusal(false, 200, 200, OWNER));
        assertNotNull(ActSpec.pointRefusal(false, 5000, 5000, OWNER));
    }
}
