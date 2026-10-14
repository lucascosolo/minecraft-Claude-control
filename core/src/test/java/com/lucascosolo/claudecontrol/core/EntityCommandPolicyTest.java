package com.lucascosolo.claudecontrol.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityCommandPolicyTest {
    private String allowed(String command) { return assertDoesNotThrow(()->EntityCommandPolicy.require(command,Set.of()),command); }
    private void refused(String command) { assertThrows(IllegalArgumentException.class,()->EntityCommandPolicy.require(command,Set.of()),command); }
    @Test void broadVanillaEntityTypesAndBenignNbtAreAvailableWithoutPassiveWhitelist() {
        for(String type:List.of("cow","sheep","horse","zombie","skeleton","creeper","armor_stand","item_display")) {
            String command="summon minecraft:"+type+" ~ ~ ~";assertEquals("minecraft:"+command,allowed(command));
        }
        String named="summon minecraft:cow ~ ~ ~ {NoAI:1b,CustomName:'{\"text\":\"Town friend\",\"color\":\"gold\"}',Glowing:1b}";
        assertEquals("minecraft:"+named,allowed(named));
        allowed("summon zombie ~ ~ ~ {NoAI:1b,Health:20.0f,PersistenceRequired:1b}");
        allowed("summon creeper ~ ~ ~ {NoAI:1b,powered:0b}");
    }
    @Test void entityMutationSelectionRidingItemsAndVisualCommandsRemainTypedVanillaOperations() {
        for(String command:List.of(
            "data get entity @e[type=minecraft:cow,limit=1,sort=nearest]",
            "data merge entity @e[type=cow,limit=1] {NoAI:1b,Glowing:1b}",
            "data modify entity @e[type=cow,limit=1] CustomName set value '\"Friend\"'",
            "data remove entity @e[type=cow,limit=1] CustomName",
            "attribute @e[type=horse,limit=1] minecraft:movement_speed base set 0.2",
            "ride @e[type=zombie,limit=1] mount @e[type=horse,limit=1]",
            "tag @e[type=cow,distance=..8] add town_friend",
            "tp @e[tag=town_friend,limit=1] ~1 ~ ~",
            "teleport @e[tag=town_friend,limit=1] ~1 ~ ~",
            "effect give @e[tag=town_friend] minecraft:glowing 10 0 true",
            "item replace entity @e[type=zombie,limit=1] weapon.mainhand with minecraft:wooden_sword",
            "particle minecraft:happy_villager ~ ~1 ~ 0 0 0 0 1",
            "playsound minecraft:entity.cow.ambient master @a ~ ~ ~ 1 1",
            "stopsound @a master minecraft:entity.cow.ambient",
            "title @a actionbar {\"text\":\"A new town friend\"}",
            "tellraw @a {\"text\":\"Meet the town friend\",\"color\":\"gold\"}")) {
            assertTrue(allowed(command).startsWith("minecraft:"),command);
        }
    }
    @Test void minecraftNamespaceNormalizationPreservesCaseAndLiteralPayloadData() {
        String arguments=" @e[type=minecraft:cow,limit=1] {CustomName:'{\"text\":\"Moo McCow\",\"color\":\"gold\"}'}";
        assertEquals("minecraft:data merge entity"+arguments,allowed("data merge entity"+arguments));
        assertEquals("minecraft:data merge entity"+arguments,allowed("minecraft:data merge entity"+arguments));
        refused("plugin:summon cow ~ ~ ~");refused("bukkit:op player");refused("minecraft:minecraft:summon cow ~ ~ ~");
    }
    @Test void executeWrappersAreBoundedAndEveryTerminalIsValidated() {
        String command="summon cow ~ ~ ~ {NoAI:1b}";
        for(int depth=1;depth<=4;depth++) {
            command="execute as @e[type=cow,limit=1] at @s run "+command;
            assertTrue(allowed(command).startsWith("minecraft:execute "));
        }
        refused("execute as @s run "+command);
        allowed("minecraft:execute as @e[type=cow,limit=1] run minecraft:tag @s add town_friend");
        for(String terminal:List.of("op player","function namespace:task","stop","plugins","summon tnt ~ ~ ~")) {
            refused("execute as @e[type=cow,limit=1] at @s run "+terminal);
        }
        refused("execute store result entity @e[limit=1] Health float 1 run data get entity @s Health");
        refused("execute summon cow run tag @s add friend");
        refused("execute as @s run return run summon cow ~ ~ ~");
    }
    @Test void administrationPluginsWorldCommandsAndNonentityDataAreRefused() {
        for(String command:List.of("op player","deop player","stop","reload","restart","plugins","version","whitelist add player",
            "ban player","save-off","function namespace:task","schedule function namespace:task 1s","sudo player op player",
            "lp user player permission set * true","fill ~ ~ ~ ~5 ~5 ~5 air","setblock ~ ~ ~ tnt","kill @e",
            "data get block ~ ~ ~","data merge block ~ ~ ~ {CustomName:'\"test\"'}","data get storage minecraft:foo",
            "data modify storage minecraft:foo value set value 1"))refused(command);
    }
    @Test void destructiveEntityFamiliesAreRefusedThroughNamespacesAndExecute() {
        for(String type:List.of("tnt","tnt_minecart","wither","ender_dragon","fireball","small_fireball","dragon_fireball",
            "end_crystal","lightning_bolt","falling_block","command_block_minecart")) {
            refused("summon "+type+" ~ ~ ~");refused("minecraft:summon minecraft:"+type+" ~ ~ ~");
            refused("execute at @s run summon minecraft:"+type+" ~ ~ ~");
        }
    }
    @Test void hazardousNbtCannotCarryCommandsOwnershipIdentityInventoriesOrExplosions() {
        for(String payload:List.of("{Command:\"op player\"}","{command:\"op player\"}",
            "{CustomName:'{\"text\":\"click\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"op player\"}}'}",
            "{CustomName:'{\"text\":\"click\",\"click_event\":{\"action\":\"run_command\",\"value\":\"stop\"}}'}",
            "{UUID:[I;1,2,3,4]}","{BukkitValues:{owner:1b}}","{Tags:[\"claudecontrol:owned\"]}",
            "{Inventory:[{id:\"minecraft:stone\",count:1}]}","{Items:[]}","{Fuse:0}","{ExplosionPower:10}",
            "{ignited:1b}","{Health:0}","{Health:0.0f}")) {
            refused("summon cow ~ ~ ~ "+payload);refused("data merge entity @e[type=cow,limit=1] "+payload);
        }
    }
    @Test void controlsBackslashObfuscationAndMalformedInputsFailClosed() {
        for(String command:List.of("summon cow ~ ~ ~\nstop","summon cow ~ ~ ~\rstop","summon\tcow ~ ~ ~",
            "summon cow ~ ~ ~\u0000", "summon cow ~ ~ ~ {CustomName:'\\u0063ommand'}", "", " ", "x".repeat(4097)))refused(command);
        assertThrows(IllegalArgumentException.class,()->EntityCommandPolicy.require(null,Set.of()));
    }
    @Test void refusalDiagnosticsDoNotEchoPrivateCommandPayloads() {
        String privatePayload="private-command-payload-canary";
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
            ()->EntityCommandPolicy.require("summon tnt ~ ~ ~ {CustomName:'\""+privatePayload+"\"'}",Set.of()));
        assertFalse(error.getMessage().contains(privatePayload));assertFalse(error.getMessage().contains("CustomName"));
        assertTrue(error.getMessage().matches("[A-Z0-9_]+"));
    }
    @Test void configuredDenialsApplyToNormalizedTerminalsAndEveryWrapper() {
        for(String command:List.of("summon cow ~ ~ ~","minecraft:summon cow ~ ~ ~","execute as @s run summon cow ~ ~ ~")) {
            assertThrows(IllegalArgumentException.class,()->EntityCommandPolicy.require(command,Set.of("summon")));
            assertThrows(IllegalArgumentException.class,()->EntityCommandPolicy.require(command,Set.of("minecraft:summon")));
        }
        assertThrows(IllegalArgumentException.class,()->EntityCommandPolicy.require("execute as @s run tag @s add friend",Set.of("execute")));
        assertDoesNotThrow(()->EntityCommandPolicy.require("tag @e[type=cow] add friend",Set.of("summon")));
    }
}
