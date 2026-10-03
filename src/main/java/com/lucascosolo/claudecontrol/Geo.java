package com.lucascosolo.claudecontrol;

/** Directions, distances and Minecraft clock text. North is -z, east is +x. */
public final class Geo {
    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private Geo() {}

    public static String compass(int dx, int dz) {
        if (dx == 0 && dz == 0) return "here";
        return point(Math.toDegrees(Math.atan2(dx, -dz)));
    }

    public static String describe(int dx, int dz) {
        if (dx == 0 && dz == 0) return "here";
        long d = Math.round(Math.hypot(dx, dz));
        return d + (d == 1 ? " block " : " blocks ") + compass(dx, dz);
    }

    /** Look direction of a yaw: 0 = south, 90 = west, 180 = north, -90 = east. */
    public static String facing(float yaw) {
        return point(yaw + 180.0);
    }

    public static String clock(long dayTimeTicks) {
        long t = Math.floorMod(dayTimeTicks + 6000, 24000L);
        return String.format("%02d:%02d", t / 1000, t % 1000 * 60 / 1000);
    }

    public static String phase(long dayTimeTicks) {
        long t = Math.floorMod(dayTimeTicks, 24000L);
        return t < 12000 ? "day" : t < 13000 ? "dusk" : t < 23000 ? "night" : "dawn";
    }

    private static String point(double degreesFromNorth) {
        return POINTS[(int) Math.floorMod(Math.round(degreesFromNorth / 45.0), 8L)];
    }
}
