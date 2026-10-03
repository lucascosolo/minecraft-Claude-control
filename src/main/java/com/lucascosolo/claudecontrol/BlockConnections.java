package com.lucascosolo.claudecontrol;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.GlassPane;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;

/**
 * Bulk placement runs without physics, so nothing recomputes neighbour shapes. This pass does it by hand (Folia exposes no
 * shape-update call) for the given cells and their direct neighbours. It runs on the region that owns the cells and skips
 * any neighbour another region owns.
 */
final class BlockConnections {
    private BlockConnections() {}

    private static final Map<Connections.Side, BlockFace> FACE = Map.of(Connections.Side.NORTH, BlockFace.NORTH,
            Connections.Side.EAST, BlockFace.EAST, Connections.Side.SOUTH, BlockFace.SOUTH, Connections.Side.WEST, BlockFace.WEST);

    /** Returns how many blocks changed shape. */
    static int apply(World world, Iterable<int[]> cells) {
        Set<Long> seen = new LinkedHashSet<>();
        Set<Block> todo = new LinkedHashSet<>();
        for (int[] c : cells) {
            for (int[] d : new int[][] {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
                int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                if (seen.add(((long) x & 0x3ffffff) << 38 | ((long) z & 0x3ffffff) << 12 | (y & 0xfff))) todo.add(world.getBlockAt(x, y, z));
            }
        }
        int changed = 0;
        for (Block b : todo) {
            // C7: a block is reshaped only when it and every neighbour it reads belong to this region.
            if (!owned(b) || !owned(b.getRelative(1, 0, 0)) || !owned(b.getRelative(-1, 0, 0)) || !owned(b.getRelative(0, 0, 1)) || !owned(b.getRelative(0, 0, -1))) continue;
            BlockData before = b.getBlockData(), after = shape(b, before.clone());
            if (!after.equals(before)) {
                b.setBlockData(after, false);
                changed++;
            }
        }
        return changed;
    }

    private static boolean owned(Block b) {
        return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) && Bukkit.isOwnedByCurrentRegion(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
    }

    private static BlockData shape(Block b, BlockData d) {
        Connections.Kind self = kind(b.getType(), d, null);
        if ((self == Connections.Kind.WOOD_FENCE || self == Connections.Kind.NETHER_FENCE || self == Connections.Kind.PANE) && d instanceof MultipleFacing m) {
            for (Connections.Side s : Connections.Side.values()) m.setFace(FACE.get(s), Connections.connects(self, neighbour(b, s), s));
        } else if (d instanceof Wall w) {
            Set<Connections.Side> on = EnumSet.noneOf(Connections.Side.class);
            for (Connections.Side s : Connections.Side.values()) if (Connections.connects(Connections.Kind.WALL, neighbour(b, s), s)) on.add(s);
            Block above = b.getRelative(BlockFace.UP);
            boolean solidAbove = owned(above) && (Tag.WALLS.isTagged(above.getType()) || above.getBlockData().isFaceSturdy(BlockFace.DOWN, BlockSupport.FULL));
            Connections.WallShape shape = Connections.wall(on, solidAbove);
            for (var e : shape.sides().entrySet()) w.setHeight(FACE.get(e.getKey()), Wall.Height.valueOf(e.getValue().toUpperCase()));
            w.setUp(shape.up());
        } else if (d instanceof Stairs st && side(st.getFacing()) != null) {
            Map<Connections.Side, Connections.Stair> around = new EnumMap<>(Connections.Side.class);
            for (Connections.Side s : Connections.Side.values()) {
                Block n = b.getRelative(FACE.get(s));
                if (owned(n) && n.getBlockData() instanceof Stairs o && side(o.getFacing()) != null) around.put(s, stair(o));
            }
            st.setShape(Stairs.Shape.valueOf(Connections.stairsShape(stair(st), around).toUpperCase()));
        } else if (d instanceof Chest c && c.getType() == Chest.Type.SINGLE && side(c.getFacing()) != null) {
            Connections.Side f = side(c.getFacing());
            for (Connections.Side s : new Connections.Side[] {f.clockwise(), f.counterClockwise()}) {
                Block n = b.getRelative(FACE.get(s));
                if (owned(n) && n.getType() == b.getType() && n.getBlockData() instanceof Chest o && o.getType() == Chest.Type.SINGLE && o.getFacing() == c.getFacing()) {
                    c.setType(Chest.Type.valueOf(Connections.chest(f, s).toUpperCase()));
                    o.setType(c.getType() == Chest.Type.LEFT ? Chest.Type.RIGHT : Chest.Type.LEFT);
                    n.setBlockData(o, false);
                    break;
                }
            }
        }
        return d;
    }

    private static Connections.Stair stair(Stairs s) {
        return new Connections.Stair(side(s.getFacing()), s.getHalf() == Bisected.Half.TOP);
    }

    private static Connections.Side side(BlockFace f) {
        for (var e : FACE.entrySet()) if (e.getValue() == f) return e.getKey();
        return null;
    }

    private static Connections.Kind neighbour(Block b, Connections.Side s) {
        Block n = b.getRelative(FACE.get(s));
        if (!owned(n)) return Connections.Kind.OTHER;
        return kind(n.getType(), n.getBlockData(), FACE.get(s.opposite()));
    }

    /** What a block is for connection purposes; toward is the face that looks back at the asking block (null: only self kinds). */
    static Connections.Kind kind(Material m, BlockData d, BlockFace toward) {
        if (m == Material.NETHER_BRICK_FENCE) return Connections.Kind.NETHER_FENCE;
        if (Tag.FENCES.isTagged(m) && d instanceof Fence) return Connections.Kind.WOOD_FENCE;
        if (d instanceof GlassPane || m.name().endsWith("_PANE") || m.name().endsWith("_BARS")) return Connections.Kind.PANE;
        if (Tag.WALLS.isTagged(m)) return Connections.Kind.WALL;
        if (Tag.FENCE_GATES.isTagged(m) && d instanceof Directional g) {
            return g.getFacing() == BlockFace.NORTH || g.getFacing() == BlockFace.SOUTH ? Connections.Kind.GATE_NS : Connections.Kind.GATE_EW;
        }
        if (toward == null || exception(m)) return Connections.Kind.OTHER;
        return d.isFaceSturdy(toward, BlockSupport.FULL) ? Connections.Kind.SOLID : Connections.Kind.OTHER;
    }

    private static boolean exception(Material m) {
        return Tag.LEAVES.isTagged(m) || Tag.SHULKER_BOXES.isTagged(m) || m == Material.BARRIER || m == Material.CARVED_PUMPKIN
                || m == Material.JACK_O_LANTERN || m == Material.MELON || m == Material.PUMPKIN;
    }
}
