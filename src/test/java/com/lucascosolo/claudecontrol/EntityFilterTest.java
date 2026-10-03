package com.lucascosolo.claudecontrol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityFilterTest {
    private static EntityFilter.Facts f(String type, boolean player, boolean hostile, boolean animal, boolean item, boolean mob) {
        return new EntityFilter.Facts(type, player, hostile, animal, item, mob);
    }

    private static final EntityFilter.Facts ZOMBIE = f("zombie", false, true, false, false, true);
    private static final EntityFilter.Facts COW = f("cow", false, false, true, false, true);
    private static final EntityFilter.Facts ITEM = f("item", false, false, false, true, false);
    private static final EntityFilter.Facts PLAYER = f("player", true, false, false, false, true);

    @Test void idsAreNormalised() {
        for (String s : List.of("zombie", "minecraft:zombie", "ZOMBIE", "Minecraft:Zombie")) {
            EntityFilter e = EntityFilter.parse(List.of(s));
            assertTrue(e.matches(ZOMBIE), s);
            assertFalse(e.matches(COW), s);
        }
    }

    @Test void groupsMatchFlags() {
        assertTrue(EntityFilter.parse(List.of("hostile")).matches(ZOMBIE));
        assertFalse(EntityFilter.parse(List.of("hostile")).matches(COW));
        assertTrue(EntityFilter.parse(List.of("animals")).matches(COW));
        assertFalse(EntityFilter.parse(List.of("animals")).matches(ZOMBIE));
        assertTrue(EntityFilter.parse(List.of("items")).matches(ITEM));
        assertFalse(EntityFilter.parse(List.of("items")).matches(COW));
        assertTrue(EntityFilter.parse(List.of("all_mobs")).matches(COW));
        assertFalse(EntityFilter.parse(List.of("all_mobs")).matches(ITEM));
    }

    @Test void multipleEntriesAreUnion() {
        EntityFilter e = EntityFilter.parse(List.of("cow", "items"));
        assertTrue(e.matches(COW));
        assertTrue(e.matches(ITEM));
        assertFalse(e.matches(ZOMBIE));
    }

    @Test void playerNeverMatches() {
        assertFalse(EntityFilter.parse(List.of("all_mobs")).matches(PLAYER));
        assertFalse(EntityFilter.parse(List.of("hostile", "animals", "items")).matches(PLAYER));
    }

    @Test void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> EntityFilter.parse(List.of()));
        for (String bad : List.of("", "  ", "zom bie", "zombie!", "zombie/x", "player", "minecraft:player")) {
            var ex = assertThrows(IllegalArgumentException.class, () -> EntityFilter.parse(List.of("cow", bad)), bad);
            if (!bad.isBlank()) assertTrue(ex.getMessage().contains(bad.trim().isEmpty() ? bad : bad.replace("minecraft:", "")), ex.getMessage());
        }
    }

    @Test void limitsTo32Entries() {
        assertDoesNotThrow(() -> EntityFilter.parse(Collections.nCopies(32, "zombie")));
        assertThrows(IllegalArgumentException.class, () -> EntityFilter.parse(Collections.nCopies(33, "zombie")));
    }
}
