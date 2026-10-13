package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CommandPolicyTest {
    private final CommandPolicy policy = new CommandPolicy(Set.of("weather"));

    @Test void permitsOrdinaryUsefulCommands() {
        assertTrue(policy.isAllowed("give Alex minecraft:stone 4"));
        assertTrue(policy.isAllowed("tp Alex 10 80 10"));
        assertTrue(policy.isAllowed("minecraft:give Alex minecraft:stone 4"));
    }

    @Test void rejectsHardDeniedAndNamespacedVariants() {
        for (String name : Set.of("op", "deop", "stop", "reload", "ban", "pardon")) {
            assertFalse(policy.isAllowed(name + " Alex"), name);
            assertFalse(policy.isAllowed("minecraft:" + name + " Alex"), name);
            assertFalse(policy.isAllowed("/MiNeCrAfT:" + name.toUpperCase() + " Alex"), name);
        }
    }

    @Test void additionalDenylistCannotWeakenHardDenials() {
        assertFalse(policy.isAllowed("weather clear"));
        assertFalse(policy.isAllowed("minecraft:weather clear"));
        assertFalse(new CommandPolicy(Set.of()).isAllowed("op Alex"));
    }

    @Test void rejectsNestedCommandWrappersAndCommandBlockPayloads() {
        for (String command : Set.of("execute run give Alex stone", "minecraft:execute as Alex run op Alex",
            "return run op Alex", "minecraft:return run give Alex stone", "give Alex command_block",
            "setblock 0 80 0 minecraft:command_block", "give Alex stone[custom_data={Command:\"op Alex\"}]")) {
            assertFalse(policy.isAllowed(command), command);
        }
    }

    @Test void rejectsEmptyAndControlContainingCommands() {
        for (String command : new String[]{"", " ", "give Alex stone\nstop", "tp Alex\rstop", "give\u0000stone", "give\tAlex stone"}) {
            assertFalse(policy.isAllowed(command));
        }
    }
}
