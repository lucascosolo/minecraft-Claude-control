package com.lucascosolo.claudecontrol.core;

import java.util.List;
import java.util.function.Predicate;

/** Pure bounded surface navigation; the caller validates loaded water and boat clearance. */
public final class WaterPlan {
    private WaterPlan() {}
    public record Motion(double x,double z) {}
    public static List<CompanionPlan.Cell> route(CompanionPlan.Cell start,CompanionPlan.Cell target,
            Predicate<CompanionPlan.Cell> loadedSurfaceWater) {
        return CompanionPlan.route(start,target,loadedSurfaceWater);
    }
    public static Motion velocity(double dx,double dz) {
        if(!Double.isFinite(dx) || !Double.isFinite(dz))throw new IllegalArgumentException("Finite boat displacement required");
        if(Math.abs(dx)>0.04)return new Motion(Math.copySign(Math.min(0.4,Math.abs(dx)),dx),0);
        if(Math.abs(dz)>0.04)return new Motion(0,Math.copySign(Math.min(0.4,Math.abs(dz)),dz));
        return new Motion(0,0);
    }
}
