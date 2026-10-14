package com.lucascosolo.claudecontrol.core;

import com.google.gson.JsonObject;
import java.util.*;

/** A fully validated, bounded block plan. Parsing never touches the world. */
public record BuildSpec(String world, int x, int y, int z, int width, int height,
                        int depth, boolean replaceExisting, List<BlockPlacement> blocks) {
    public record BlockPlacement(int x, int y, int z, String material,String blockData) {
        public BlockPlacement(int x,int y,int z,String material){this(x,y,z,material,"");}
    }
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
        Map<String,BlockPlacement> planned = new LinkedHashMap<>();
        Set<String> explicit = new HashSet<>();int shapes=0;long visits=0;
        for (var element : operations) {
            if(!element.isJsonObject())throw new IllegalArgumentException("Block operation must be an object");
            JsonObject op = element.getAsJsonObject();
            int bx = integer(op,"x"), by = integer(op,"y"), bz = integer(op,"z");
            int ex=bx,ey=by,ez=bz;String mode="solid";
            boolean shape=op.has("shape");
            if(shape) {
                if(!text(op,"shape").equals("box") || ++shapes>128)throw new IllegalArgumentException("Use at most 128 compact box shapes");
                ex=op.has("x2")?integer(op,"x2"):bx;ey=op.has("y2")?integer(op,"y2"):by;ez=op.has("z2")?integer(op,"z2"):bz;
                mode=op.has("mode")?text(op,"mode"):"solid";
                if(!Set.of("solid","shell","omit").contains(mode))throw new IllegalArgumentException("Box mode must be solid, shell or omit");
            } else if(op.has("x2") || op.has("y2") || op.has("z2") || op.has("mode"))throw new IllegalArgumentException("Cuboid endpoints require shape=box");
            if(bx<0 || by<0 || bz<0 || ex<bx || ey<by || ez<bz || ex>=width || ey>=height || ez>=depth)
                throw new IllegalArgumentException("Invalid relative block bounds");
            visits+=(long)(ex-bx+1)*(ey-by+1)*(ez-bz+1);
            if(visits>65536)throw new IllegalArgumentException("Compact expansion exceeds 65536 inspected cells; split the project into stages");
            String material=mode.equals("omit")?null:text(op,"material");
            if(material!=null && (!safeMaterial(material) || material.equals("AIR")))throw new IllegalArgumentException("Unsafe material");
            String blockData=op.has("block_data")?text(op,"block_data"):"";
            if(op.has("block_data"))validateBlockData(blockData,material);
            if(!shape && !explicit.add(bx+":"+by+":"+bz))throw new IllegalArgumentException("Duplicate explicit block coordinate");
            for(int ix=bx;ix<=ex;ix++)for(int iy=by;iy<=ey;iy++)for(int iz=bz;iz<=ez;iz++) {
                if(mode.equals("shell") && ix!=bx && ix!=ex && iy!=by && iy!=ey && iz!=bz && iz!=ez)continue;
                String key=ix+":"+iy+":"+iz;
                if(mode.equals("omit"))planned.remove(key); // Edits this unplaced plan only; never clears a world block.
                else planned.put(key,new BlockPlacement(ix,iy,iz,material,blockData));
            }
        }
        if(planned.isEmpty())throw new IllegalArgumentException("Build plan has no remaining placements");
        return new BuildSpec(world,x,y,z,width,height,depth,replace,List.copyOf(planned.values()));
    }
    private static void validateBlockData(String data,String material){
        if(material==null || data.length()>512 || !data.matches("minecraft:[a-z0-9_]+(?:\\[[a-z0-9_]+=[a-z0-9_]+(?:,[a-z0-9_]+=[a-z0-9_]+)*\\])?"))
            throw new IllegalArgumentException("Expected bounded Minecraft block data without NBT");
        int bracket=data.indexOf('[');String base=bracket<0?data:data.substring(0,bracket);
        if(!base.equals("minecraft:"+material.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Block data must match material");
        if(bracket>=0){Set<String> properties=new HashSet<>();for(String property:data.substring(bracket+1,data.length()-1).split(",")){
            if(!properties.add(property.substring(0,property.indexOf('='))))throw new IllegalArgumentException("Duplicate block property");
            if(property.equals("waterlogged=true"))throw new IllegalArgumentException("Fluid-bearing placement is unavailable");
        }}
    }
    private static String text(JsonObject input,String name) {
        var value=input.get(name);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Expected text: "+name);
        return value.getAsString();
    }
}
