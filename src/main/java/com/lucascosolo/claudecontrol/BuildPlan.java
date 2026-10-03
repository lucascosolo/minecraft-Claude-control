package com.lucascosolo.claudecontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A validated box of block operations in local coordinates (x east, y up, z south, all from 0). */
public final class BuildPlan {
    public record Op(String block, int[] from, int[] to, boolean hollow) {}

    public static final class InvalidPlan extends RuntimeException {
        public InvalidPlan(String message) { super(message); }
    }

    private static final List<String> FORBIDDEN = List.of("command_block", "structure_block", "jigsaw", "barrier", "bedrock", "{");
    private static final List<String> BUILT = List.of("planks", "cobblestone", "bricks", "glass", "door", "chest", "bed", "wool",
            "concrete", "stairs", "slab", "fence", "torch", "lantern", "furnace", "crafting_table", "sign", "carpet", "terracotta");

    private final int width, depth, height;
    private final List<Op> ops;

    private BuildPlan(int width, int depth, int height, List<Op> ops) {
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.ops = ops;
    }

    static final int MAX_CELLS = 4096;

    public static BuildPlan parse(int width, int depth, int height, List<Op> ops) {
        return parse(width, depth, height, ops, false);
    }

    public static BuildPlan parse(int width, int depth, int height, List<Op> ops, boolean clear) {
        for (int d : new int[] {width, depth, height}) {
            if (d < 1 || d > 16) throw new InvalidPlan("width, depth and height must each be 1..16.");
        }
        if (ops.isEmpty() || ops.size() > 64) throw new InvalidPlan("ops must hold 1..64 operations.");
        BuildPlan plan = new BuildPlan(width, depth, height, List.copyOf(ops));
        long cells = clear ? (long) width * depth * height : 0;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op.block() == null || op.block().isBlank()) throw new InvalidPlan("op " + i + ": block is blank.");
            if (isForbidden(op.block())) throw new InvalidPlan("op " + i + ": block " + op.block() + " is not allowed.");
            if (!plan.contains(op.from()) || !plan.contains(op.to())) {
                throw new InvalidPlan("op " + i + ": from and to must be [x,y,z] inside the box (x 0.." + (width - 1)
                        + ", y 0.." + (height - 1) + ", z 0.." + (depth - 1) + ").");
            }
            cells += (long) (Math.abs(op.to()[0] - op.from()[0]) + 1) * (Math.abs(op.to()[1] - op.from()[1]) + 1) * (Math.abs(op.to()[2] - op.from()[2]) + 1);
        }
        if (cells > MAX_CELLS) throw new InvalidPlan("the ops change " + cells + " cells; at most " + MAX_CELLS + " per call.");
        return plan;
    }

    public int width() { return width; }

    public int depth() { return depth; }

    public int height() { return height; }

    public List<Op> ops() { return ops; }

    /** Whether p is three ints naming a cell inside the box. */
    public boolean contains(int[] p) {
        return p != null && p.length == 3 && p[0] >= 0 && p[0] < width && p[1] >= 0 && p[1] < height && p[2] >= 0 && p[2] < depth;
    }

    public List<int[]> positions(Op op) {
        int x0 = Math.min(op.from()[0], op.to()[0]), x1 = Math.max(op.from()[0], op.to()[0]);
        int y0 = Math.min(op.from()[1], op.to()[1]), y1 = Math.max(op.from()[1], op.to()[1]);
        int z0 = Math.min(op.from()[2], op.to()[2]), z1 = Math.max(op.from()[2], op.to()[2]);
        List<int[]> out = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    boolean face = x == x0 || x == x1 || y == y0 || y == y1 || z == z0 || z == z1;
                    if (!op.hollow() || face) out.add(new int[] {x, y, z});
                }
            }
        }
        return out;
    }

    public static boolean isForbidden(String block) {
        String b = block.toLowerCase(Locale.ROOT);
        return FORBIDDEN.stream().anyMatch(b::contains);
    }

    public static boolean looksBuilt(String material) {
        String m = material.toLowerCase(Locale.ROOT);
        return BUILT.stream().anyMatch(m::contains);
    }
}
