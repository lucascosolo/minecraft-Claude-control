package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * J1: the set path applies the I4 refusals to a resolved non-player relation.
 * ASSUMED signature (primitives, as ActSpec.targetRefusal / ridersRefusal take them):
 * TraitSpec.relatedRefusal(String key, String tamedOwner, boolean named, boolean villagerOrGolem, List<String> riders, Authority a)
 */
class TraitSetRelatedRefusalTest {
    private static final Authority SAM = new Authority(List.of("Sam"), false, false, false);
    private static final Authority OWNER = new Authority(List.of("Sam"), true, false, false);

    @Test void targetMatchesTargetAction() {
        assertEquals(ActSpec.targetRefusal("Bob", false, false, SAM), TraitSpec.relatedRefusal("target", "Bob", false, false, List.of(), SAM));
        assertEquals(ActSpec.targetRefusal(null, true, false, SAM), TraitSpec.relatedRefusal("target", null, true, false, List.of(), SAM));
        assertEquals(ActSpec.targetRefusal(null, false, true, SAM), TraitSpec.relatedRefusal("target", null, false, true, List.of(), SAM));
        assertNotNull(TraitSpec.relatedRefusal("target", "Bob", false, false, List.of(), SAM));
    }

    @Test void ridingAndPassengerMatchMountAction() {
        for (String k : List.of("riding", "passenger")) {
            String expect = ActSpec.ridersRefusal(List.of("Zed"), SAM);
            assertNotNull(expect);
            assertEquals(expect, TraitSpec.relatedRefusal(k, null, false, false, List.of("Zed"), SAM));
            assertNull(TraitSpec.relatedRefusal(k, null, false, false, List.of("sam"), SAM));
        }
    }

    @Test void privilegedAndUnremarkableAreNotRefused() {
        assertNull(TraitSpec.relatedRefusal("target", "Bob", true, true, List.of("Zed"), OWNER));
        assertNull(TraitSpec.relatedRefusal("riding", "Bob", true, true, List.of("Zed"), OWNER));
        assertNull(TraitSpec.relatedRefusal("target", null, false, false, List.of(), SAM));
        assertNull(TraitSpec.relatedRefusal("passenger", null, false, false, List.of(), SAM));
    }
}
