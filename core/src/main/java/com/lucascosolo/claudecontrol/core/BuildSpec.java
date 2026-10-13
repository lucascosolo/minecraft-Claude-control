package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import java.util.*;

/** A fully validated, bounded block plan. Parsing never touches the world. */
public record BuildSpec(String world, int x, int y, int z, int width, int height,
                        int depth, boolean replaceExisting, List<BlockPlacement> blocks) {
    public record BlockPlacement(int x, int y, int z, String material) {}
    private static final Set<String> HAZARDS = Set.of("TNT", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK",
        "REPEATING_COMMAND_BLOCK", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "BARRIER", "BEDROCK",
        "LAVA", "WATER", "FIRE", "SOUL_FIRE", "RESPAWN_ANCHOR", "END_PORTAL", "END_PORTAL_FRAME",
        "NETHER_PORTAL", "SPAWNER", "TRIAL_SPAWNER", "VAULT", "SCULK_CATALYST", "SCULK_SHRIEKER",
        "DRAGON_EGG", "WITHER_SKELETON_SKULL", "WITHER_SKELETON_WALL_SKULL", "LIGHT",
        "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE", "POWDER_SNOW", "SWEET_BERRY_BUSH", "WITHER_ROSE",
        "POINTED_DRIPSTONE", "ICE", "FROSTED_ICE",
        "PISTON", "STICKY_PISTON", "PISTON_HEAD", "MOVING_PISTON", "DISPENSER", "DROPPER", "HOPPER",
        "REDSTONE_BLOCK", "REDSTONE_WIRE", "REDSTONE_TORCH", "REDSTONE_WALL_TORCH", "OBSERVER",
        "LEVER", "REPEATER", "COMPARATOR", "TRIPWIRE", "TRIPWIRE_HOOK", "FARMLAND", "SAND", "RED_SAND", "GRAVEL");
    public static boolean safeMaterial(String material) {
        return material != null && material.matches("[A-Z][A-Z0-9_]{0,70}") && !HAZARDS.contains(material)
            && !material.endsWith("_BUTTON") && !material.endsWith("_PRESSURE_PLATE")
            && !material.endsWith("_CONCRETE_POWDER") && !material.endsWith("_ANVIL") && !material.equals("ANVIL");
    }
    public static int integer(JsonObject input, String name) {
        try {
            var value = input.get(name);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException();
            return value.getAsBigDecimal().intValueExact();
        } catch (RuntimeException e) { throw new IllegalArgumentException("Expected integer: " + name); }
    }
    public static BuildSpec parse(JsonObject input) {
        String world = input.has("world") ? input.get("world").getAsString() : "";
        if (world.isBlank() || world.length() > 128) throw new IllegalArgumentException("World name required");
        int x = integer(input,"x"), y = integer(input,"y"), z = integer(input,"z");
        int width = integer(input,"width"), height = integer(input,"height"), depth = integer(input,"depth");
        if (Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000 || y < -2048 || y > 2048)
            throw new IllegalArgumentException("Origin out of bounds");
        if (width < 1 || width > 32 || height < 1 || height > 32 || depth < 1 || depth > 32
            || (long)width * height * depth > 8192 || (long)x + width > 30_000_000 || (long)z + depth > 30_000_000)
            throw new IllegalArgumentException("Build must fit 32 blocks per side and 8192 blocks total");
        boolean replace = false;
        if (input.has("replace_existing")) {
            var value = input.get("replace_existing");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid replacement flag");
            replace = value.getAsBoolean();
        }
        if (!input.has("operations") || !input.get("operations").isJsonArray()) throw new IllegalArgumentException("Block operations required");
        var operations = input.getAsJsonArray("operations");
        if (operations.isEmpty() || operations.size() > 8192) throw new IllegalArgumentException("Between 1 and 8192 operations required");
        List<BlockPlacement> blocks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var element : operations) {
            JsonObject op = element.getAsJsonObject();
            int bx = integer(op,"x"), by = integer(op,"y"), bz = integer(op,"z");
            String material = op.get("material").getAsString();
            if (bx < 0 || bx >= width || by < 0 || by >= height || bz < 0 || bz >= depth
                || !seen.add(bx + ":" + by + ":" + bz)) throw new IllegalArgumentException("Invalid or duplicate relative block coordinate");
            if (!safeMaterial(material)) throw new IllegalArgumentException("Unsafe material");
            blocks.add(new BlockPlacement(bx,by,bz,material));
        }
        return new BuildSpec(world,x,y,z,width,height,depth,replace,List.copyOf(blocks));
    }
}
