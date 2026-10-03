package com.lucascosolo.claudecontrol;

import static com.lucascosolo.claudecontrol.TestJson.j;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

// Contract section 3: selector tag and EntitySelector.Ref.
class EntitySelectorRefTest {
    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final EntityFilter.Facts ZOMBIE = new EntityFilter.Facts("zombie", false, true, false, false, true);
    private static final EntityFilter.Facts PLAYER = new EntityFilter.Facts("player", true, false, false, false, false);

    private static EntitySelector.Ref ref(String json) { return EntitySelector.Ref.parse(j(json)); }
    private static void badRef(String json) { assertThrows(IllegalArgumentException.class, () -> ref(json), json); }

    // --- tag
    @Test void tagLowerCasedAndValidated() {
        assertEquals("post", EntitySelector.tag("POST"));
        assertEquals("a_1", EntitySelector.tag("a_1"));
        assertEquals("x".repeat(24), EntitySelector.tag("x".repeat(24)));
        for (String bad : new String[] {"", "a b", "a-b", "x".repeat(25)})
            assertThrows(IllegalArgumentException.class, () -> EntitySelector.tag(bad), bad);
    }

    @Test void parseReadsTag() {
        var s = EntitySelector.parse(j("{'tag':'Post'}"), 10);
        assertEquals("post", s.tag());
        assertTrue(s.ids().isEmpty());
        assertNull(s.filter());
    }

    @Test void parseNeedsOneOfTypesIdsTag() {
        assertThrows(IllegalArgumentException.class, () -> EntitySelector.parse(j("{}"), 10));
        assertDoesNotThrow(() -> EntitySelector.parse(j("{'types':['hostile']}"), 10));
        assertDoesNotThrow(() -> EntitySelector.parse(j("{'ids':['" + A + "']}"), 10));
        assertDoesNotThrow(() -> EntitySelector.parse(j("{'tag':'x'}"), 10));
    }

    @Test void parseRejectsBadTag() {
        assertThrows(IllegalArgumentException.class, () -> EntitySelector.parse(j("{'tag':'a b'}"), 10));
    }

    @Test void compatConstructorHasNullTag() {
        var s = EntitySelector.parse(j("{'types':['hostile']}"), 10);
        assertNull(s.tag());
        assertNull(new EntitySelector(Set.of(), s.filter()).tag());
    }

    @Test void threeArgMatchesRequiresTag() {
        var s = EntitySelector.parse(j("{'tag':'post'}"), 10);
        assertTrue(s.matches(A, ZOMBIE, Set.of("post", "other")));
        assertFalse(s.matches(A, ZOMBIE, Set.of("other")));
        assertFalse(s.matches(A, ZOMBIE, Set.of()));
    }

    @Test void threeArgCombinesIdsFilterAndTag() {
        var s = EntitySelector.parse(j("{'types':['hostile'],'tag':'post'}"), 10);
        assertTrue(s.matches(A, ZOMBIE, Set.of("post")));
        assertFalse(s.matches(A, ZOMBIE, Set.of()));
        var cow = new EntityFilter.Facts("cow", false, false, true, false, true);
        assertFalse(s.matches(A, cow, Set.of("post")));
    }

    @Test void threeArgWithoutTagIgnoresTags() {
        var s = EntitySelector.parse(j("{'types':['hostile']}"), 10);
        assertTrue(s.matches(A, ZOMBIE, Set.of()));
    }

    @Test void twoArgMatchesFalseForTagSelector() {
        assertFalse(EntitySelector.parse(j("{'tag':'post'}"), 10).matches(A, ZOMBIE));
        assertFalse(EntitySelector.parse(j("{'types':['hostile'],'tag':'post'}"), 10).matches(A, ZOMBIE));
    }

    @Test void playersNeverMatch() {
        var s = EntitySelector.parse(j("{'types':['hostile','animal','mob','all']}"), 10);
        assertFalse(s.matches(A, PLAYER, Set.of()));
        var byTag = EntitySelector.parse(j("{'tag':'post'}"), 10);
        assertFalse(byTag.matches(A, PLAYER, Set.of("post")));
        var byId = EntitySelector.parse(j("{'ids':['" + A + "']}"), 10);
        assertFalse(byId.matches(A, PLAYER, Set.of()));
    }

    // --- Ref
    @Test void refSelfAndNearestPlayer() {
        var s = ref("'self'");
        assertEquals(EntitySelector.Ref.Kind.SELF, s.kind());
        assertNull(s.value());
        var n = ref("'nearest_player'");
        assertEquals(EntitySelector.Ref.Kind.NEAREST_PLAYER, n.kind());
        assertEquals(32, n.radius());
    }

    @Test void refParam() {
        var r = ref("'$owner'");
        assertEquals(EntitySelector.Ref.Kind.PARAM, r.kind());
        assertEquals("owner", r.value());
        assertEquals("a_1", ref("'$a_1'").value());
        assertEquals("_x", ref("'$_x'").value());
    }

    @Test void refParamNameRules() {
        badRef("'$'");
        badRef("'$1a'");
        badRef("'$Owner'");
        badRef("'$a-b'");
        badRef("'$" + "a".repeat(25) + "'");
        assertEquals("a".repeat(24), ref("'$" + "a".repeat(24) + "'").value());
    }

    @Test void refUuidLowerCased() {
        var r = ref("'" + A.toUpperCase().replace('1', 'A') + "'");
        assertEquals(EntitySelector.Ref.Kind.ENTITY, r.kind());
        assertEquals(("" + A).replace('1', 'a'), r.value());
    }

    @Test void refPlayerName() {
        var r = ref("'Steve_1'");
        assertEquals(EntitySelector.Ref.Kind.PLAYER, r.kind());
        assertEquals("Steve_1", r.value());
        assertEquals(EntitySelector.Ref.Kind.PLAYER, ref("'" + "a".repeat(16) + "'").kind());
        badRef("'" + "a".repeat(17) + "'");
    }

    @Test void refEntityObject() {
        var e = ref("{'entity':'" + A + "'}");
        assertEquals(EntitySelector.Ref.Kind.ENTITY, e.kind());
        assertEquals(A, e.value());
        var t = ref("{'entity':'Post'}");
        assertEquals(EntitySelector.Ref.Kind.TAG, t.kind());
        assertEquals("post", t.value());
        badRef("{'entity':'a b'}");
        badRef("{'entity':''}");
    }

    @Test void refNearestDefaultsAndBounds() {
        var r = ref("{'nearest':{'types':['hostile']}}");
        assertEquals(EntitySelector.Ref.Kind.NEAREST, r.kind());
        assertEquals(16, r.radius());
        assertNotNull(r.nearest());
        assertNotNull(r.nearest().filter());
        assertEquals(32, ref("{'nearest':{'types':['hostile']},'radius':32}").radius());
        badRef("{'nearest':{'types':['hostile']},'radius':33}");
        assertEquals(1, ref("{'nearest':{'types':['hostile']},'radius':1}").radius());
        badRef("{'nearest':{'types':['hostile']},'radius':0}");
        badRef("{'nearest':{'types':['hostile']},'radius':65}");
    }

    @Test void refNearestByTag() {
        var r = ref("{'nearest':{'tag':'post'}}");
        assertEquals("post", r.nearest().tag());
    }

    @Test void refNearestNeedsValidSelector() {
        badRef("{'nearest':{}}");
    }

    @Test void refRejections() {
        badRef("5");
        badRef("''");
        badRef("'a b'");
        badRef("null");
        badRef("{}");
        badRef("{'bogus':'x'}");
        badRef("[]");
    }
}
